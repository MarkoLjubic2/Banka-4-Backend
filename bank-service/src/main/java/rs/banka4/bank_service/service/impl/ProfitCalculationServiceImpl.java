package rs.banka4.bank_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import rs.banka4.bank_service.domain.actuaries.db.MonetaryAmount;
import rs.banka4.bank_service.domain.options.db.Asset;
import rs.banka4.bank_service.domain.options.db.Option;
import rs.banka4.bank_service.domain.options.db.OptionType;
import rs.banka4.bank_service.domain.orders.db.Direction;
import rs.banka4.bank_service.domain.orders.db.Order;
import rs.banka4.bank_service.domain.security.forex.db.ForexPair;
import rs.banka4.bank_service.domain.security.future.db.Future;
import rs.banka4.bank_service.domain.security.stock.db.Stock;
import rs.banka4.bank_service.exceptions.AssetNotFound;
import rs.banka4.bank_service.repositories.ListingRepository;
import rs.banka4.bank_service.repositories.OrderRepository;
import rs.banka4.bank_service.service.abstraction.ProfitCalculationService;

/**
 * A unified calculator that, for any asset type (Stock, Future, ForexPair, Option), determines
 * profit using Fifo method for profit calculation Returns unrealized profit
 */
@Service
@RequiredArgsConstructor
public class ProfitCalculationServiceImpl implements ProfitCalculationService {
    private final OrderRepository orderRepository;
    private final ListingRepository listingRepository;

    /**
     * Calculates total profit (unrealized) for the given user and asset.
     */
    public MonetaryAmount calculateProfit(UUID userId, Asset asset, MonetaryAmount currentPrice) {
        if (asset instanceof Option option) {
            return calculateOptionProfit(currentPrice, option);
        }

        var buyOrders =
            orderRepository.findByUserIdAndAssetAndDirectionAndIsDone(
                userId,
                asset,
                Direction.BUY,
                true
            );
        var sellOrders =
            orderRepository.findByUserIdAndAssetAndDirectionAndIsDone(
                userId,
                asset,
                Direction.SELL,
                true
            );
        sellOrders.addAll(
            orderRepository.findByUserIdAndAssetAndDirectionAndIsDone(
                userId,
                asset,
                Direction.SELL,
                false
            )
                .stream()
                .peek(o -> o.setQuantity(o.getQuantity() - o.getRemainingPortions()))
                .toList()
        );

        buyOrders.sort(Comparator.comparing(Order::getCreatedAt));
        sellOrders.sort(Comparator.comparing(Order::getCreatedAt));

        var lots = new LinkedList<Lot>();
        for (Order buy : buyOrders) {
            var qty = BigDecimal.valueOf(buy.getQuantity());
            var baseCost =
                buy.getPricePerUnit()
                    .getAmount()
                    .multiply(qty);
            var fee = computeFee(buy, baseCost);

            BigDecimal totalCost = baseCost.add(fee);

            lots.add(new Lot(qty, totalCost));
        }

        for (Order sell : sellOrders) {
            var sellQty = BigDecimal.valueOf(sell.getQuantity());

            while (sellQty.compareTo(BigDecimal.ZERO) > 0 && !lots.isEmpty()) {
                var firstLot = lots.getFirst();
                int cmp = firstLot.quantity.compareTo(sellQty);
                if (cmp > 0) {
                    var fraction = sellQty.divide(firstLot.quantity, 4, RoundingMode.HALF_UP);
                    var costForThisSell = firstLot.cost.multiply(fraction);

                    firstLot.cost = firstLot.cost.subtract(costForThisSell);
                    firstLot.quantity = firstLot.quantity.subtract(sellQty);
                    sellQty = BigDecimal.ZERO;
                } else if (cmp == 0) {
                    firstLot.quantity = BigDecimal.ZERO;
                    lots.removeFirst();
                    sellQty = BigDecimal.ZERO;
                } else {
                    sellQty = sellQty.subtract(firstLot.quantity);
                    firstLot.quantity = BigDecimal.ZERO;
                    lots.removeFirst();
                }
            }
        }

        var leftoverQty = BigDecimal.ZERO;
        var leftoverCost = BigDecimal.ZERO;

        for (Lot lot : lots) {
            leftoverQty = leftoverQty.add(lot.quantity);
            leftoverCost = leftoverCost.add(lot.cost);
        }

        if (leftoverQty.compareTo(BigDecimal.ZERO) <= 0) {
            return new MonetaryAmount(BigDecimal.ZERO, currentPrice.getCurrency());
        }

        var avgBuyPrice = leftoverCost.divide(leftoverQty, 4, RoundingMode.HALF_UP);

        var multiplier = getMultiplier(asset);

        var unrealized =
            currentPrice.getAmount()
                .subtract(avgBuyPrice)
                .multiply(leftoverQty)
                .multiply(multiplier);

        return new MonetaryAmount(unrealized, currentPrice.getCurrency());
    }

    /**
     * Calculate realized profit (P&L) for exactly one sell order, using FIFO matching against prior
     * buys (including partial fills), and accounting for both buy- and sell-side fees.
     *
     * @param sellOrder the SELL {@link Order} for which we want realized profit
     * @return a {@link MonetaryAmount} in the sell order’s currency
     */
    public MonetaryAmount calculateRealizedProfitForSell(Order sellOrder) {
        var userId =
            sellOrder.getUser()
                .getId();
        var asset = sellOrder.getAsset();
        var sellQty =
            BigDecimal.valueOf(sellOrder.getQuantity() - (sellOrder.getRemainingPortions()));
        var sellGross =
            sellOrder.getPricePerUnit()
                .getAmount()
                .multiply(sellQty);
        var sellFee = computeFee(sellOrder, sellGross);
        var sellNet = sellGross.subtract(sellFee);

        var buyOrders =
            orderRepository.findByUserIdAndAssetAndDirectionAndIsDone(
                userId,
                asset,
                Direction.BUY,
                true
            )
                .stream()
                .filter(
                    o -> !o.getLastModified()
                        .isAfter(sellOrder.getLastModified())
                )
                .sorted(Comparator.comparing(Order::getCreatedAt))
                .toList();

        var lots = new ArrayDeque<>();
        for (var buy : buyOrders) {
            var qty = BigDecimal.valueOf(buy.getQuantity());
            var grossCost =
                buy.getPricePerUnit()
                    .getAmount()
                    .multiply(qty);
            var fee = computeFee(buy, grossCost);
            lots.addLast(new Lot(qty, grossCost.add(fee)));
        }

        var remainingToMatch = sellQty;
        var consumedCost = BigDecimal.ZERO;

        while (remainingToMatch.compareTo(BigDecimal.ZERO) > 0 && !lots.isEmpty()) {
            Lot head = (Lot) lots.peekFirst();
            int cmp = head.quantity.compareTo(remainingToMatch);

            if (cmp > 0) {
                var fraction = remainingToMatch.divide(head.quantity, 8, RoundingMode.HALF_UP);
                var costForPortion = head.cost.multiply(fraction);
                consumedCost = consumedCost.add(costForPortion);

                head.cost = head.cost.subtract(costForPortion);
                head.quantity = head.quantity.subtract(remainingToMatch);
                remainingToMatch = BigDecimal.ZERO;

            } else {
                consumedCost = consumedCost.add(head.cost);
                remainingToMatch = remainingToMatch.subtract(head.quantity);
                lots.removeFirst();
            }
        }
        /// This will never throw
        if (remainingToMatch.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("You are trying to sell more then you have");
        }

        var profit = sellNet.subtract(consumedCost);
        return new MonetaryAmount(
            profit,
            sellOrder.getPricePerUnit()
                .getCurrency()
        );
    }

    /**
     * Calculates profit (unrealized/realized) for the given option.
     */
    public MonetaryAmount calculateOptionProfit(Option option) {
        var optionalListing =
            listingRepository.getLatestListing(
                option.getStock()
                    .getId(),
                Limit.of(1)
            );
        var currentPrice =
            optionalListing.map(
                listing -> new MonetaryAmount(
                    listing.getBid(),
                    listing.getExchange()
                        .getCurrency()
                )
            )
                .orElseThrow(AssetNotFound::new);
        return calculateOptionProfit(currentPrice, option);
    }

    private BigDecimal computeFee(Order order, BigDecimal baseCost) {
        return switch (order.getOrderType()) {
        case MARKET -> {
            BigDecimal mFee = baseCost.multiply(BigDecimal.valueOf(0.14));
            yield mFee.min(BigDecimal.valueOf(7));
        }
        case LIMIT -> {
            BigDecimal lFee = baseCost.multiply(BigDecimal.valueOf(0.24));
            yield lFee.min(BigDecimal.valueOf(12));
        }
        default -> BigDecimal.ZERO;
        };
    }

    private MonetaryAmount calculateOptionProfit(MonetaryAmount monetaryCurrPrice, Option option) {
        final var contractMultiplier = BigDecimal.valueOf(100);
        var currentStockPrice = monetaryCurrPrice.getAmount();
        var strikePrice =
            option.getStrikePrice()
                .getAmount();
        var premium =
            option.getPremium()
                .getAmount();

        var intrinsic = getIntrinsic(option, currentStockPrice, strikePrice);

        var totalIntrinsicValue = intrinsic.multiply(contractMultiplier);
        var totalPremium = premium.multiply(contractMultiplier);

        var profit = totalIntrinsicValue.subtract(totalPremium);
        profit = profit.setScale(2, RoundingMode.HALF_UP);
        return new MonetaryAmount(profit, monetaryCurrPrice.getCurrency());
    }

    private static BigDecimal getIntrinsic(
        Option option,
        BigDecimal currentStockPrice,
        BigDecimal strikePrice
    ) {
        BigDecimal intrinsic;
        if (option.getOptionType() == OptionType.CALL) {
            intrinsic = currentStockPrice.subtract(strikePrice);
        } else if (option.getOptionType() == OptionType.PUT) {
            intrinsic = strikePrice.subtract(currentStockPrice);
        } else {
            throw new IllegalArgumentException(
                "Unsupported option type: " + option.getOptionType()
            );
        }
        return intrinsic.max(BigDecimal.ZERO);
    }


    /**
     * Distinguishes each asset type by returning a multiplier for the final profit. - Stock -> 1 -
     * Future -> future.getContractSize() - Forex -> 1 - Option -> 100 (as specification says) or
     * use an option-specific contractSize if stored
     */
    private BigDecimal getMultiplier(Asset asset) {
        if (asset instanceof Stock) {
            return BigDecimal.ONE;
        } else if (asset instanceof Future fut) {
            return BigDecimal.valueOf(fut.getContractSize());
        } else if (asset instanceof ForexPair) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE;
    }

    @AllArgsConstructor
    @Data
    private static class Lot {
        private BigDecimal quantity;
        private BigDecimal cost;
    }
}
