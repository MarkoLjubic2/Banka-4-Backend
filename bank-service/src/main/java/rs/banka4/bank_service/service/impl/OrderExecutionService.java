package rs.banka4.bank_service.service.impl;

import jakarta.transaction.Transactional;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import rs.banka4.bank_service.domain.orders.db.Direction;
import rs.banka4.bank_service.domain.orders.db.Order;
import rs.banka4.bank_service.domain.orders.db.Status;
import rs.banka4.bank_service.exceptions.InsufficientVolume;
import rs.banka4.bank_service.repositories.OrderRepository;
import rs.banka4.bank_service.service.abstraction.ListingService;
import rs.banka4.bank_service.service.abstraction.TaxService;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final OrderRepository orderRepository;
    private final ListingService listingService;
    private final TaxService taxService;

    /**
     * Processes an order in an all-or-nothing manner. If a matching order is found, it executes the
     * order. If no matching order is found, it returns false.
     *
     * @param order The order to be processed.
     * @return A CompletableFuture indicating the success of the operation.
     */
    @Async("orderExecutor")
    @Transactional
    public CompletableFuture<Boolean> processAllOrNothingOrderAsync(Order order) {
        log.info("[AON] Starting async processing for order {}", order.getId());

        Optional<Order> match =
            orderRepository.findMatchingOrders(
                order.getAsset()
                    .getId(),
                oppositeDirection(order.getDirection()),
                Status.APPROVED,
                order.getQuantity(),
                PageRequest.of(0, 1)
            )
                .stream()
                .findFirst();

        if (match.isEmpty()) {
            log.warn("[AON] No single matching order can fulfill order {}.", order.getId());
            return CompletableFuture.completedFuture(false);
        }

        Order matchedOrder = match.get();
        matchedOrder.setRemainingPortions(
            matchedOrder.getRemainingPortions() - order.getQuantity()
        );
        if (matchedOrder.getRemainingPortions() == 0) {
            matchedOrder.setDone(true);
            matchedOrder.setUsed(true);
        }

        order.setRemainingPortions(0);
        order.setDone(true);
        order.setUsed(true);
        orderRepository.save(order);

        // TODO: Make transaction between order's client and matchedOrder's client
        /**
         * Calculates and records tax for a SELL order. BUY orders are ignored.
         * <p>
         * If you persist SELL orders in multiple places, be sure to invoke this method wherever
         * those saves occur; otherwise, a single call here is sufficient.
         *
         * @param order the order just saved; only SELL orders trigger tax processing
         */
        taxService.addTaxForOrderToDB(order);
        orderRepository.save(matchedOrder);

        log.info(
            "[AON] Order {} fully executed against order {}.",
            order.getId(),
            matchedOrder.getId()
        );

        return CompletableFuture.completedFuture(true);
    }


    /**
     * Processes an order in a partial manner. It executes the order in chunks until all portions
     * are executed or no matching orders are found.
     *
     * @param order The order to be processed.
     * @return A CompletableFuture indicating the success of the operation.
     */
    @Async("orderExecutor")
    @Transactional
    public CompletableFuture<Boolean> processPartialOrderAsync(Order order) {
        log.info("[Partial] Starting async processing for order {}", order.getId());

        /*
         * Lock the order to prevent concurrent modifications.
         */
        Order lockedOrder =
            orderRepository.findByIdWithLock(order.getId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + order.getId()));

        int remainingPortions = lockedOrder.getRemainingPortions();
        int chunk = getChunk(remainingPortions);
        int executedPortions = 0;
        Order matchedOrder = null;

        try {
            Optional<Order> match =
                orderRepository.findMatchingOrders(
                    lockedOrder.getAsset()
                        .getId(),
                    oppositeDirection(lockedOrder.getDirection()),
                    Status.APPROVED,
                    remainingPortions,
                    PageRequest.of(0, 1)
                )
                    .stream()
                    .findFirst();

            if (match.isEmpty()) {
                log.warn("[Partial] No matching order found for order {}.", lockedOrder.getId());
                return CompletableFuture.completedFuture(false);
            }
            matchedOrder = match.get();

            while (remainingPortions > 0) {
                int portionsToExecute = Math.min(remainingPortions, chunk);

                log.info(
                    "[Partial] Executing {} portions of order {} against order {}.",
                    portionsToExecute,
                    lockedOrder.getId(),
                    matchedOrder.getId()
                );

                matchedOrder.setRemainingPortions(
                    matchedOrder.getRemainingPortions() - portionsToExecute
                );
                lockedOrder.setRemainingPortions(
                    lockedOrder.getRemainingPortions() - portionsToExecute
                );
                executedPortions += portionsToExecute;
                remainingPortions -= portionsToExecute;

                if (matchedOrder.getRemainingPortions() == 0) {
                    matchedOrder.setDone(true);
                    matchedOrder.setUsed(true);
                }
                orderRepository.save(matchedOrder);
                orderRepository.save(lockedOrder);

                // TODO: Make transaction between order's client and matchedOrder's client
                /**
                 * Calculates and records tax for a SELL order. BUY orders are ignored.
                 * <p>
                 * If you persist SELL orders in multiple places, be sure to invoke this method
                 * wherever those saves occur; otherwise, a single call here is sufficient.
                 *
                 * @param order the order just saved; only SELL orders trigger tax processing
                 */
                taxService.addTaxForOrderToDB(order);
                log.info(
                    "[Partial] After execution: remainingPortions is {} for order {}.",
                    remainingPortions,
                    lockedOrder.getId()
                );
                int waitTimeSeconds =
                    calculateWaitTime(lockedOrder.getQuantity(), remainingPortions);
                try {
                    log.warn(
                        "[Partial] Waiting {} seconds before processing next chunk.",
                        waitTimeSeconds
                    );
                    TimeUnit.SECONDS.sleep(waitTimeSeconds);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                    throw new RuntimeException("Interrupted during partial order processing", e);
                }

                if (remainingPortions == 0) {
                    log.info("[Partial] Order {} fully executed.", lockedOrder.getId());
                    break;
                }

                chunk = getChunk(remainingPortions);

                /*
                 * Check if the matched order is still valid. If not, rollback the changes. This is
                 * to ensure that we are not executing against an order that has been modified or
                 * deleted.
                 */
                Optional<Order> checkMatch = orderRepository.findById(matchedOrder.getId());
                if (
                    checkMatch.isEmpty()
                        || checkMatch.get()
                            .getRemainingPortions()
                            < remainingPortions
                ) {
                    log.warn(
                        "[Partial] No matching order found for order {}.",
                        lockedOrder.getId()
                    );
                    throw new InsufficientVolume();
                }

            }

            if (lockedOrder.getRemainingPortions() == 0) {
                lockedOrder.setDone(true);
                lockedOrder.setUsed(true);
            }

            orderRepository.save(lockedOrder);

            return CompletableFuture.completedFuture(true);

        } catch (InsufficientVolume e) {
            /*
             * Rollback the matched orders to their previous state.
             */
            if (matchedOrder == null) return CompletableFuture.completedFuture(false);
            matchedOrder.setRemainingPortions(
                matchedOrder.getRemainingPortions() + executedPortions
            );
            if (matchedOrder.isDone()) {
                matchedOrder.setDone(false);
                matchedOrder.setUsed(false);
            }
            orderRepository.save(matchedOrder);
            lockedOrder.setRemainingPortions(remainingPortions + executedPortions);
            orderRepository.save(lockedOrder);

            log.warn(
                "[Partial] Rolled back order {} due to insufficient volume.",
                lockedOrder.getId()
            );
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Generates a random chunk size based on the remaining portions.
     *
     * @param remainingPortions The remaining portions of the order.
     * @return A random chunk size.
     */
    private int getChunk(int remainingPortions) {
        return (1 + new Random().nextInt(remainingPortions));
    }

    /**
     * Calculates the wait time based on the volume and remaining portions.
     *
     * @param volume The volume of the order.
     * @param remainingPortions The remaining portions of the order.
     * @return A random wait time in seconds.
     */
    private int calculateWaitTime(int volume, int remainingPortions) {
        int maxSeconds = 24 * 60 / (volume / Math.max(1, remainingPortions));
        return new Random().nextInt(Math.max(1, maxSeconds));
    }

    private Direction oppositeDirection(Direction direction) {
        return direction == Direction.BUY ? Direction.SELL : Direction.BUY;
    }
}
