package rs.banka4.bank_service.tx.executor;

import static java.util.function.Predicate.*;
import static rs.banka4.bank_service.tx.TxUtils.isTxBalanced;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import rs.banka4.bank_service.domain.account.db.Account;
import rs.banka4.bank_service.domain.actuaries.db.MonetaryAmount;
import rs.banka4.bank_service.domain.options.db.Option;
import rs.banka4.bank_service.domain.options.db.OptionType;
import rs.banka4.bank_service.domain.security.stock.db.Stock;
import rs.banka4.bank_service.domain.trading.db.ForeignBankId;
import rs.banka4.bank_service.domain.trading.db.OtcRequest;
import rs.banka4.bank_service.domain.trading.db.RequestStatus;
import rs.banka4.bank_service.domain.transaction.db.Transaction;
import rs.banka4.bank_service.domain.user.User;
import rs.banka4.bank_service.exceptions.InvalidTxAsset;
import rs.banka4.bank_service.repositories.AccountRepository;
import rs.banka4.bank_service.repositories.OptionsRepository;
import rs.banka4.bank_service.repositories.OtcRequestRepository;
import rs.banka4.bank_service.repositories.StockRepository;
import rs.banka4.bank_service.repositories.TransactionRepository;
import rs.banka4.bank_service.repositories.UserRepository;
import rs.banka4.bank_service.service.abstraction.AccountService;
import rs.banka4.bank_service.service.abstraction.AssetOwnershipService;
import rs.banka4.bank_service.service.abstraction.BankAccountService;
import rs.banka4.bank_service.service.abstraction.ExchangeRateService;
import rs.banka4.bank_service.tx.TxExecutor;
import rs.banka4.bank_service.tx.TxUtils;
import rs.banka4.bank_service.tx.config.InterbankConfig;
import rs.banka4.bank_service.tx.data.CommitTransaction;
import rs.banka4.bank_service.tx.data.DoubleEntryTransaction;
import rs.banka4.bank_service.tx.data.IdempotenceKey;
import rs.banka4.bank_service.tx.data.Message;
import rs.banka4.bank_service.tx.data.MonetaryAsset;
import rs.banka4.bank_service.tx.data.NoVoteReason;
import rs.banka4.bank_service.tx.data.OptionDescription;
import rs.banka4.bank_service.tx.data.Posting;
import rs.banka4.bank_service.tx.data.RollbackTransaction;
import rs.banka4.bank_service.tx.data.StockDescription;
import rs.banka4.bank_service.tx.data.TransactionVote;
import rs.banka4.bank_service.tx.data.TxAccount;
import rs.banka4.bank_service.tx.data.TxAsset;
import rs.banka4.bank_service.tx.errors.MessagePrepFailedException;
import rs.banka4.bank_service.tx.errors.TxLocalPartVotedNo;
import rs.banka4.bank_service.tx.executor.db.ExecutingTransaction;
import rs.banka4.bank_service.tx.executor.db.ExecutingTransactionRepository;
import rs.banka4.bank_service.tx.executor.db.InboxMessage;
import rs.banka4.bank_service.tx.executor.db.InboxRepository;
import rs.banka4.bank_service.tx.executor.db.OutboxMessage;
import rs.banka4.bank_service.tx.executor.db.OutboxMessageId;
import rs.banka4.bank_service.tx.executor.db.OutboxRepository;
import rs.banka4.bank_service.tx.otc.config.InterbankRetrofitProvider;
import rs.banka4.rafeisen.common.currency.CurrencyCode;

/**
 * A transaction executor capable of delivering transactions across banks.
 */
@Service
@Slf4j
public class InterbankTxExecutor implements TxExecutor, ApplicationRunner {
    private final InterbankConfig interbankConfig;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;
    private final AccountRepository accountRepo;
    private final AccountService accountService;
    private final OutboxRepository outboxRepo;
    private final ExecutingTransactionRepository execTxRepo;
    private final TaskScheduler taskScheduler;
    private final InterbankRetrofitProvider interbanks;
    private final InboxRepository inboxRepo;
    private final UserRepository userRepo;
    private final StockRepository stockRepo;
    private final OptionsRepository optionsRepo;
    private final OtcRequestRepository otcRequestRepo;
    private final AssetOwnershipService assetOwnershipService;
    private final TransactionRepository userFacingTxRepo;
    private final BankAccountService bankAccountService;
    private final ExchangeRateService exchangeRateService;

    /* Synchronization key for transaction execution. */
    private final Object transactionKey = new Object();

    public InterbankTxExecutor(
        InterbankConfig config,
        PlatformTransactionManager transactionManager,
        ObjectMapper objectMapper,
        AccountRepository accountRepo,
        OutboxRepository outboxRepo,
        ExecutingTransactionRepository execTxRepo,
        TaskScheduler taskScheduler,
        InterbankRetrofitProvider interbanks,
        InboxRepository inboxRepo,
        UserRepository userRepo,
        StockRepository stockRepo,
        OptionsRepository optionsRepo,
        OtcRequestRepository otcRequestRepository,
        AssetOwnershipService assetOwnershipService,
        AccountService accountService,
        TransactionRepository userFacingTxRepo,
        BankAccountService bankAccountService,
        ExchangeRateService exchangeRateService
    ) {
        this.interbankConfig = config;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        this.objectMapper = objectMapper;
        this.accountRepo = accountRepo;
        this.accountService = accountService;
        this.outboxRepo = outboxRepo;
        this.execTxRepo = execTxRepo;
        this.taskScheduler = taskScheduler;
        this.interbanks = interbanks;
        this.inboxRepo = inboxRepo;
        this.userRepo = userRepo;
        this.stockRepo = stockRepo;
        this.optionsRepo = optionsRepo;
        this.otcRequestRepo = otcRequestRepository;
        this.assetOwnershipService = assetOwnershipService;
        this.userFacingTxRepo = userFacingTxRepo;
        this.bankAccountService = bankAccountService;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    protected IdempotenceKey newIdempotenceKey() {
        final var key =
            new IdempotenceKey(
                ForeignBankId.OUR_ROUTING_NUMBER,
                UUID.randomUUID()
                    .toString()
            );

        /* TODO(arsen): detect reuse */
        return key;
    }

    private Set<Long> collectAndValidateDestinations(DoubleEntryTransaction tx) {
        final var dests = TxUtils.collectDestinations(tx);
        final var validDests =
            new HashSet<>(
                interbankConfig.getRoutingTable()
                    .keySet()
            );
        validDests.add(ForeignBankId.OUR_ROUTING_NUMBER);
        if (!validDests.containsAll(dests))
            throw new IllegalArgumentException(
                "Destination(s) %s are invalid".formatted(
                    dests.stream()
                        .filter(not(validDests::contains))
                        .toList()
                )
            );
        return dests;
    }

    private Optional<Stock> resolveStock(StockDescription stockDescription) {
        return stockRepo.findByTicker(stockDescription.ticker());
    }


    /* ==== Stock postings. Used when stocks are being exchanged. ==== */
    private Optional<NoVoteReason> personStockPostingPhase1(
        User person,
        StockDescription assetDescription,
        Posting posting
    ) {
        final var asset_ = resolveStock(assetDescription);
        if (asset_.isEmpty()) return Optional.of(new NoVoteReason.NoSuchAsset(posting));
        final var asset = asset_.get();

        final int amount;
        try {
            amount =
                posting.amount()
                    .intValueExact();
        } catch (ArithmeticException ignored) {
            return Optional.of(new NoVoteReason.InsufficientAsset(posting));
        }

        if (amount >= 0)
            /* Nothing to reserve, this is a debit. */
            return Optional.empty();

        if (
            !assetOwnershipService.changeAssetOwnership(
                asset,
                person,
                /* Note that, if we're here, amount < 0. */
                +amount,
                0,
                -amount
            )
        ) return Optional.of(new NoVoteReason.InsufficientAsset(posting));

        return Optional.empty();
    }

    private void personStockPostingPhase1Rollback(
        User person,
        StockDescription assetDescription,
        Posting posting
    ) {
        final var asset =
            stockRepo.findByTicker(assetDescription.ticker())
                .orElseThrow(() -> new IllegalStateException("invalid tx?"));

        final int amount;
        try {
            amount =
                posting.amount()
                    .min(BigDecimal.ZERO)
                    .intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("invalid tx?", e);
        }

        if (
            /* Roll back the reservation. */
            !assetOwnershipService.changeAssetOwnership(asset, person, -amount, 0, +amount)
        ) throw new IllegalStateException("invalid tx?");
    }

    private void personStockPostingPhase2(
        User person,
        StockDescription assetDescription,
        Posting posting
    ) {
        final var asset =
            stockRepo.findByTicker(assetDescription.ticker())
                .orElseThrow(() -> new IllegalStateException("invalid tx?"));

        final int amount;
        try {
            amount =
                posting.amount()
                    .intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("invalid tx?", e);
        }

        /* @formatter:off
         * By reasoning similar to Note [Phase-by-phase balance changes], let P be the private
         * reservation amount before phase 1, P' be private ownership amount after phase 1, and
         * P_r be private ownership amount after phase 2.  Let, also, R with respective
         * indices/superscripts indicate the same states of ownership reservation.  Let X be the
         * amount of the posting.
         *
         * Our goal is that P_r = P + X, and R_r = R.
         *
         * P' = P + \min(X, 0), and R' = P - \min(X, 0) as a result of P' = P and R' = R if X >= 0,
         * and P' = P + X and R' = R - X otherwise
         *
         * Lets express P and R in terms of P' and R'.  P = P' - \min(X, 0); R = R' + \min(X, 0).
         * Hence, P_r = P' - \min(X, 0) + X; R_r = R' + \min(X, 0).  By reasoning similar to Note
         * [Phase-by-phase balance changes].
         *
         * @formatter:on
         */

        if (
            /* Commit the transaction. */
            !assetOwnershipService.changeAssetOwnership(
                asset,
                person,
                -Math.min(amount, 0) + amount,
                0,
                +Math.min(amount, 0)
            )
        ) throw new IllegalStateException("invalid tx?");
    }

    private Map<ForeignBankId, UUID> remoteToLocalOptionNameMap = new ConcurrentHashMap<>();

    private Optional<Option> resolveOptionDescription(OptionDescription optDesc) {
        final var stock = resolveStock(optDesc.stock());
        if (stock.isEmpty()) return Optional.empty();
        return Optional.of(
            optionsRepo.save(
                new Option(
                    remoteToLocalOptionNameMap.computeIfAbsent(
                        optDesc.negotiationId(),
                        k -> UUID.randomUUID()
                    ),
                    null,
                    null,
                    stock.get(),
                    OptionType.CALL,
                    optDesc.pricePerUnit(),
                    /* Processed otherwise. */
                    new MonetaryAmount(
                        BigDecimal.ZERO,
                        optDesc.pricePerUnit()
                            .getCurrency()
                    ),
                    0.,
                    0,
                    optDesc.settlementDate(),
                    false,
                    optDesc.negotiationId()
                )
            )
        );
    }


    /* ==== Option creation. Used when an OTC option is made from a sellers' stocks. ==== */
    private Optional<NoVoteReason> createAndReserveOptionPhase1(
        User person,
        OptionDescription optDesc,
        Posting posting
    ) {
        final var negotiation_ = otcRequestRepo.findById(optDesc.negotiationId());
        if (negotiation_.isEmpty())
            return Optional.of(new NoVoteReason.OptionNegotiationNotFound(posting));
        final var negotiation = negotiation_.get();

        final var option_ = resolveOptionDescription(optDesc);
        if (option_.isEmpty()) return Optional.of(new NoVoteReason.NoSuchAsset(posting));
        final var option = option_.get();

        if (
            !assetOwnershipService.changeAssetOwnership(
                option.getStock(),
                person,
                0,
                -optDesc.amount(),
                +optDesc.amount()
            )
        ) return Optional.of(new NoVoteReason.InsufficientAsset(posting));

        negotiation.setOptionId(option.getId());
        otcRequestRepo.save(negotiation);
        return Optional.empty();
    }

    private void createAndReserveOptionPhase1Rollback(
        User person,
        OptionDescription optDesc,
        Posting posting
    ) {
        final var negotiation =
            otcRequestRepo.findById(optDesc.negotiationId())
                .orElseThrow(() -> new IllegalStateException("invalid option tx"));
        negotiation.setOptionId(null);
        otcRequestRepo.save(negotiation);
        final var stock =
            stockRepo.findByTicker(
                optDesc.stock()
                    .ticker()
            )
                .orElseThrow(() -> new IllegalStateException("invalid option tx"));

        if (
            !assetOwnershipService.changeAssetOwnership(
                stock,
                person,
                0,
                +optDesc.amount(),
                -optDesc.amount()
            )
        ) throw new IllegalStateException("invalid option tx?");
    }

    private void createAndReserveOptionPhase2(
        User person,
        OptionDescription optDesc,
        Posting posting
    ) {
        final var negotiation =
            otcRequestRepo.findById(optDesc.negotiationId())
                .orElseThrow(() -> new IllegalStateException("invalid option tx"));
        negotiation.setStatus(RequestStatus.FINISHED);
        otcRequestRepo.save(negotiation);
    }


    /* ==== Option deposits. Used when an OTC option is given to a buyer. ==== */
    private Optional<NoVoteReason> depositOptionPhase1(
        User person,
        OptionDescription optDesc,
        Posting posting
    ) {
        final var negotiation_ = otcRequestRepo.findById(optDesc.negotiationId());
        if (negotiation_.isEmpty())
            return Optional.of(new NoVoteReason.OptionNegotiationNotFound(posting));
        final var negotiation = negotiation_.get();

        final var option_ = resolveOptionDescription(optDesc);
        if (option_.isEmpty()) return Optional.of(new NoVoteReason.NoSuchAsset(posting));
        final var option = option_.get();
        negotiation.setOptionId(option.getId());
        otcRequestRepo.save(negotiation);
        return Optional.empty();
    }

    private void depositOptionPhase1Rollback(
        User person,
        OptionDescription optDesc,
        Posting posting
    ) {
        final var negotiation =
            otcRequestRepo.findById(optDesc.negotiationId())
                .orElseThrow(() -> new IllegalStateException("invalid option tx"));
        negotiation.setOptionId(null);
        otcRequestRepo.save(negotiation);
    }

    private void depositOptionPhase2(User person, OptionDescription optDesc, Posting posting) {
        final var negotiation =
            otcRequestRepo.findById(optDesc.negotiationId())
                .orElseThrow(() -> new IllegalStateException("invalid option tx"));
        negotiation.setStatus(RequestStatus.FINISHED);
        otcRequestRepo.save(negotiation);
        if (
            !assetOwnershipService.changeAssetOwnership(
                negotiation.getOptionId(),
                person.getId(),
                1,
                0,
                0
            )
        ) throw new IllegalStateException("invalid option tx?");
    }


    /* ==== Option execution. Used when an option gets used. ==== */
    private Optional<Pair<OtcRequest, Option>> resolveOptionPseudo(ForeignBankId foreignOptionId) {
        return optionsRepo.findByFBId(foreignOptionId)
            .flatMap(
                o -> otcRequestRepo.findByOptionId(o.getId())
                    .map(r -> Pair.of(r, o))
            );
    }

    private void commitOptionExecute(OtcRequest offer, Option option, Posting posting) {
        /* @formatter:off
         * A transaction wants to execute an option.  The option is formed like so:
         *
         * In order to execute an option, an Executing Bank should form a transaction of the form:
         *
         * - Debit option pseudo-account for p*k where p is the price per unit stock and k is the
         *   amount of stocks in the option, <<<<
         * - Credit the buyer for p*k
         * - Credit option pseudo-account for k stocks,    <<<<
         * - Debit relevant receiving accounts for k assets
         *
         * The bank shall, upon the correct usage of an option, mark the option as used and prevent
         * further usage of it.
         *
         * Lines marked with "<<<<" are relevant here.
         * @formatter:off
         */

        final var seller = offer.getMadeFor();
        assert seller.routingNumber() == ForeignBankId.OUR_ROUTING_NUMBER;
        final var sellerUuid = UUID.fromString(seller.id());

        switch (posting.asset()) {
            case TxAsset.Monas(MonetaryAsset(CurrencyCode currency)) -> {
                final var depositAcc =
                    accountService.getRequiredAccount(
                        sellerUuid,
                        currency,
                        BigDecimal.ZERO
                    )
                        .orElseThrow(() -> new IllegalStateException("Invalid tx?"));

                final var realAcc =
                    accountRepo.getAccountByAccountNumber(depositAcc.accountNumber())
                            .orElseThrow(() -> new IllegalStateException("Invalid tx?"));
                realAcc.setAvailableBalance(realAcc.getAvailableBalance().add(posting.amount()));
                realAcc.setBalance(realAcc.getBalance().add(posting.amount()));
            }
            case TxAsset.Stock(StockDescription sd) -> {
                final var stock = resolveStock(sd).orElseThrow(() -> new IllegalStateException("Invalid tx?"));
                assert posting.amount().compareTo(new BigDecimal(-offer.getAmount())) == 0;
                if (!assetOwnershipService.changeAssetOwnership(stock.getId(), sellerUuid, 0, 0, -offer.getAmount()))
                    throw new IllegalStateException("Invalid tx?");
            }
            default -> throw new IllegalStateException("Invalid tx?");
        }
    }

    /**
     * Perform phase one of local transaction execution:
     *
     * <p>
     * <strong>You probably don't want to call this directly.</strong.
     *
     * <blockquote> Firstly, upon receiving a transaction from an Initiating Bank (IB), a
     * transaction is <i>prepared</i>: all credited accounts have the transacted amount of assets
     * <i>reserved</i>. If this was not possible (for instance, because an account would be
     * overdrafted), the transaction fails locally, the failure is recorded, and a NO vote is cast
     * (voting is described later). Otherwise, a YES vote is cast. </blockquote>
     *
     * <p>
     * This function is not transactional: on failure, it will leave half-written crap in the DB.
     *
     * <p>
     * Caller should hold {@link #transactionKey}.
     *
     * @throws TxLocalPartVotedNo if the local part of this transaction fails
     */
    public void executeLocalPhase1(DoubleEntryTransaction tx) {
        if (!isTxBalanced(tx))
            throw new TxLocalPartVotedNo(tx, List.of(new NoVoteReason.UnbalancedTx()));

        final var noReasons = new ArrayList<NoVoteReason>();
        final var weSetAsFinished = new HashSet<ForeignBankId>();
        for (final var posting : tx.postings()) {
            if (
                posting.account()
                        .routingNumber()
                != ForeignBankId.OUR_ROUTING_NUMBER
            ) continue;

            switch (posting.account()) {
                case TxAccount.Person(ForeignBankId personId) -> {
                    final var person_ = userRepo.findById(UUID.fromString(personId.id()));
                    if (!person_.isPresent()) {
                        noReasons.add(new NoVoteReason.NoSuchAccount(posting));
                        continue;
                    }
                    final var person = person_.get();

                    switch (posting.asset()) {
                        case TxAsset.Monas(MonetaryAsset asset)
                            -> throw new IllegalArgumentException(
                                "must preprocess tx with resolvePersonMonetaryAssetPostings"
                            );
                        case TxAsset.Stock(StockDescription asset)
                            -> personStockPostingPhase1(person, asset, posting).ifPresent(
                                noReasons::add
                            );
                        case TxAsset.Option(OptionDescription option) -> {
                            if (
                                posting.amount()
                                        .compareTo(new BigDecimal("-1")) == 0
                            ) {
                                createAndReserveOptionPhase1(person, option, posting).ifPresent(
                                    noReasons::add
                                );
                            } else
                                if (
                                    posting.amount()
                                            .compareTo(new BigDecimal("1")) == 0
                                ) {
                                    depositOptionPhase1(person, option, posting).ifPresent(
                                        noReasons::add
                                    );
                                } else {
                                    noReasons.add(new NoVoteReason.InsufficientAsset(posting));
                                }
                        }
                    }
                }

                case TxAccount.Option(ForeignBankId optionId) -> {
                    /* @formatter:off
                     * A transaction wants to execute an option.  The option is formed like so:
                     *
                     * In order to execute an option, an Executing Bank should form a transaction of
                     * the form:
                     *
                     * - Debit option pseudo-account for p*k where p is the price per unit stock and
                     *   k is the amount of stocks in the option,      <<<<
                     * - Credit the buyer for p*k
                     * - Credit option pseudo-account for k stocks,    <<<<
                     * - Debit relevant receiving accounts for k assets
                     *
                     * The bank shall, upon the correct usage of an option, mark the option as used
                     * and prevent further usage of it.
                     *
                     * Lines marked with "<<<<" are relevant here.
                     * @formatter:off
                     */
                    final var offerOption_ = resolveOptionPseudo(optionId);
                    if (offerOption_.isEmpty()) {
                        noReasons.add(new NoVoteReason.NoSuchAccount(posting));
                        continue;
                    }
                    final var offerOption = offerOption_.get();
                    final var offer = offerOption.getLeft();
                    final var option = offerOption.getRight();

                    if (offer.getStatus() != RequestStatus.FINISHED
                        && !weSetAsFinished.contains(optionId)) {
                        noReasons.add(new NoVoteReason.OptionUsedOrExpired(posting));
                        continue;
                    }

                    switch (posting.asset()) {
                        case TxAsset.Stock(StockDescription(String ticker)) -> {
                            if (!option.getStock().getTicker().equals(ticker)) {
                                noReasons.add(new NoVoteReason.InsufficientAsset(posting));
                                continue;
                            }
                            if (posting.amount().compareTo(new BigDecimal(-offer.getAmount())) != 0) {
                                noReasons.add(new NoVoteReason.OptionAmountIncorrect(posting));
                                continue;
                            }
                        }
                        case TxAsset.Monas(MonetaryAsset(CurrencyCode currency)) -> {
                            final var pricePer = option.getStrikePrice();
                            if (!pricePer.getCurrency().equals(currency)) {
                                noReasons.add(new NoVoteReason.UnacceptableAsset(posting));
                                continue;
                            }
                            if (
                                pricePer.getAmount()
                                        .multiply(new BigDecimal(offer.getAmount()))
                                        .compareTo(posting.amount()) != 0
                            ) {
                                noReasons.add(new NoVoteReason.OptionAmountIncorrect(posting));
                                continue;
                            }
                            assert posting.amount().signum() >= 0;
                            final var seller = offer.getMadeFor();
                            assert seller.routingNumber() == ForeignBankId.OUR_ROUTING_NUMBER;
                            if (
                                accountService.getRequiredAccount(
                                    UUID.fromString(seller.id()),
                                    currency,
                                    BigDecimal.ZERO
                                ).isEmpty()
                            ) {
                                noReasons.add(new NoVoteReason.UnacceptableAsset(posting));
                                continue;
                            }
                        }

                        default -> {
                            noReasons.add(new NoVoteReason.UnacceptableAsset(posting));
                            continue;
                        }
                    }

                    /* Reserve it. */
                    offer.setStatus(RequestStatus.USED);
                    weSetAsFinished.add(optionId);
                    otcRequestRepo.save(offer);
                }

                case TxAccount.Account(String accNumber) -> {
                    final var accMby = accountRepo.findAccountByAccountNumber(accNumber);
                    if (!accMby.isPresent()) {
                        noReasons.add(new NoVoteReason.NoSuchAccount(posting));
                        continue;
                    }
                    final var acc = accMby.get();

                    if (
                        /* Monetary assets are the only kind depositable to accounts. */
                        !(posting.asset() instanceof TxAsset.Monas(MonetaryAsset asset))
                            /* ... but their currencies must match. */
                            || !asset.currency()
                                    .equals(acc.getCurrency())
                    ) {
                        noReasons.add(new NoVoteReason.UnacceptableAsset(posting));
                        continue;
                    }

                    /* See Note [Phase-by-phase balance changes]. */
                    final var newAvBalance =
                        acc.getAvailableBalance()
                                .add(
                                    posting.amount()
                                            .min(BigDecimal.ZERO)
                                );

                    if (newAvBalance.signum() < 0) {
                        noReasons.add(new NoVoteReason.InsufficientAsset(posting));
                        continue;
                    }

                    acc.setAvailableBalance(newAvBalance);
                    accountRepo.save(acc);
                }

                case TxAccount.MemoryHole() -> {
                    /* Always OK. */
                }
            }
        }

        if (!noReasons.isEmpty())
            throw new TxLocalPartVotedNo(tx, noReasons);
    }

    /**
     * Roll back phase one of local transaction execution:
     *
     * <p>
     * <strong>You probably don't want to call this directly.</strong.
     *
     * <blockquote>
     * <p>
     * Should a transaction fail after it has been
     * <a href="https://arsen.srht.site/si-tx-proto/#orga35c5c5">locally prepared</a>, it can be
     * rolled back by un-reserving all resources reserved by local credit postings in the original
     * transaction. The transaction should also be marked as failed in the transaction log.
     * </p>
     *
     * <p>
     * It is impossible for a transaction to be rolled back after a
     * <a href="https://arsen.srht.site/si-tx-proto/#org09040a6">local commit</a>.
     * </p>
     * </blockquote>
     *
     * <p>
     * In addition, updates all {@link Transaction} statuses accordingly.
     *
     * <p>
     * Caller should hold {@link #transactionKey}.
     *
     * @returns A list of reasons not to accept a transaction. The caller is expected to make the
     *          transaction roll back if the list is non-empty.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rollbackLocalPhase1(DoubleEntryTransaction tx) {
        for (final var posting : tx.postings()) {
            if (
                posting.account()
                    .routingNumber()
                    != ForeignBankId.OUR_ROUTING_NUMBER
            ) continue;

            switch (posting.account()) {
            case TxAccount.Person(ForeignBankId personId) -> {
                final var person =
                    userRepo.findById(UUID.fromString(personId.id()))
                        .orElseThrow(() -> new IllegalStateException("invalid tx"));
                switch (posting.asset()) {
                case TxAsset.Monas(MonetaryAsset asset)
                    -> throw new IllegalArgumentException(
                        "must preprocess tx with resolvePersonMonetaryAssetPostings before P1"
                    );
                case TxAsset.Stock(StockDescription asset)
                    -> personStockPostingPhase1Rollback(person, asset, posting);
                case TxAsset.Option(OptionDescription option) -> {
                    if (
                        posting.amount()
                            .compareTo(new BigDecimal(-1)) == 0
                    ) {
                        createAndReserveOptionPhase1Rollback(person, option, posting);
                    } else
                        if (
                            (posting.amount()
                                .compareTo(new BigDecimal(1))) == 0
                        ) {
                            depositOptionPhase1Rollback(person, option, posting);
                        } else {
                            throw new IllegalStateException("Invalid tx?");
                        }
                }
                }
            }

            case TxAccount.Option(ForeignBankId optionId) -> {
                final var offerOption = resolveOptionPseudo(optionId)
                        .orElseThrow(() -> new IllegalStateException("Invalid tx?"));

                offerOption.getLeft().setStatus(RequestStatus.FINISHED);
                otcRequestRepo.save(offerOption.getLeft());
            }

            case TxAccount.Account(String accNumber) -> {
                final var acc =
                    accountRepo.findAccountByAccountNumber(accNumber)
                        .orElseThrow(() -> new IllegalStateException("Invalid tx?"));

                acc.setAvailableBalance(
                    acc.getAvailableBalance()
                        .subtract(
                            posting.amount()
                                .min(BigDecimal.ZERO)
                        )
                );
                accountRepo.save(acc);
            }

            case TxAccount.MemoryHole() -> {
                /* Always OK. */
            }
            }
        }

        execTxRepo.setTransactionStatusForExecutingTransactionConstituents(
            tx.transactionId(),
            /* Collides with TransactionStatus from Spring :( */
            rs.banka4.bank_service.domain.transaction.db.TransactionStatus.REJECTED
        );
    }

    /**
     * Perform phase two of local transaction execution:
     *
     * <blockquote> Should all involved parties vote YES, the IB will send, to all involved parties,
     * a <i>commit</i> message. Upon receiving a commit message, the bank shall erase previously
     * reserved resources, and <i>debit</i> any new assets. </blockquote>
     *
     * <p>
     * In addition, updates all {@link Transaction} statuses accordingly.
     *
     * <p>
     * Note that {@code tx} <strong>must be valid</strong>. No validation will be performed.
     *
     * <p>
     * Caller should hold {@link #transactionKey}.
     *
     * @returns A list of reasons not to accept a transaction. The caller is expected to make the
     *          transaction roll back if the list is non-empty.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    protected void executeLocalPhase2(DoubleEntryTransaction tx) {
        for (final var posting : tx.postings()) {
            if (posting.account() instanceof TxAccount.Option option) {
                /* HACK: take the option away from everyone.  This is a hack because it is Monday,
                 * 18 hours before this suffering that befell us ends.  In reality, the option
                 * should be disposed of via a posting.  I don't care, however.
                 *
                 * Each option is guaranteed a unique owner as a result of the fact that each
                 * negotiation produces at most one option, and only one buyer exists per
                 * negotiation.
                 */
                optionsRepo.findByFBId(option.id()).ifPresent(opt ->
                    assetOwnershipService.clearOwnership(opt.getId()));
            }

            if (
                posting.account()
                    .routingNumber()
                    != ForeignBankId.OUR_ROUTING_NUMBER
            ) continue;

            switch (posting.account()) {
            case TxAccount.Person(ForeignBankId personId) -> {
                final var person =
                    userRepo.findById(UUID.fromString(personId.id()))
                        .orElseThrow(() -> new IllegalStateException("invalid tx"));
                switch (posting.asset()) {
                case TxAsset.Monas(MonetaryAsset asset)
                    -> throw new IllegalArgumentException(
                        "must preprocess tx with resolvePersonMonetaryAssetPostings before P1"
                    );
                case TxAsset.Stock(StockDescription asset)
                    -> personStockPostingPhase2(person, asset, posting);
                case TxAsset.Option(OptionDescription option) -> {
                    if (
                        posting.amount()
                            .compareTo(new BigDecimal(-1)) == 0
                    ) {
                        createAndReserveOptionPhase2(person, option, posting);
                    } else
                        if (
                            posting.amount()
                                .compareTo(new BigDecimal(1)) == 0
                        ) {
                            depositOptionPhase2(person, option, posting);
                        } else {
                            throw new IllegalStateException("Invalid tx?");
                        }
                }
                }
            }

            case TxAccount.Option(ForeignBankId optionId) -> {
                final var offerOption = resolveOptionPseudo(optionId)
                        .orElseThrow(() -> new IllegalStateException("Invalid tx?"));
                commitOptionExecute(offerOption.getLeft(), offerOption.getRight(), posting);
            }

            case TxAccount.Account(String accNumber) -> {
                final var acc =
                    accountRepo.findAccountByAccountNumber(accNumber)
                        .orElseThrow(() -> new IllegalStateException("Invalid tx?"));

                /* @formatter:off
                 * Note [Phase-by-phase balance changes]
                 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                 *
                 * Let A be the available balance, B the (total) balance, and X be the amount of the
                 * posting.  If X is negative, the posting is a credit, otherwise, it is a debit.
                 * Let A_r and B_r be the resulting available and total balance.  Let A' and B' be
                 * the available and total balance at the start of phase two (commit).
                 *
                 * After a successful transaction, A_r = A + X, B_r = B + X.  Case by case, at the
                 * end of phase one:
                 *
                 * |        | A'    | B' |
                 * |--------+-------+----|
                 * | Debit  | A     | B  |
                 * | Credit | A + X | B  |
                 *
                 * Notice, however, that in the debit case, A' = A = A + \min(0, X) (since X >= 0).
                 * In the credit case, \min(0, X) = X (since X < 0), so, both cases can be boiled
                 * down to A' = A + \min(0, X).
                 *
                 * Hence, the result of phase one is A' = A + \min(0, X), B' = B.
                 *
                 * Phase two needs to finish the transaction, and make A_r = A + X and B_r = B + X.
                 * Given that A = A' - \min(0, X), it follows that A_r = A' - \min(0, X) + X.
                 *
                 * Case by case:
                 *
                 * | X <=> 0 | -\min(0, X) + X             |
                 * |---------+-----------------------------|
                 * | X < 0   | -X + X = 0                  |
                 * | X >= 0  | -0 + X = X                  |
                 * | always  | -\min(0, X) + X = max(0, X) |
                 *
                 * Hence, A_r = A' + max(0, X).
                 *
                 * With a much simpler derivation, B_r = B' + X.
                 * @formatter:on
                 */

                acc.setBalance(
                    acc.getBalance()
                        .add(posting.amount())
                );
                acc.setAvailableBalance(
                    acc.getAvailableBalance()
                        .add(
                            posting.amount()
                                .max(BigDecimal.ZERO)
                        )
                );
                accountRepo.save(acc);
            }

            case TxAccount.MemoryHole() -> {
                /* Always OK. */
            }
            }
        }

        execTxRepo.setTransactionStatusForExecutingTransactionConstituents(
            tx.transactionId(),
            /* Collides with TransactionStatus from Spring :( */
            rs.banka4.bank_service.domain.transaction.db.TransactionStatus.REALIZED
        );
    }

    /**
     * Records the transaction with a single vote, presuming that we voted yes locally.
     *
     * @return The local transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    protected ExecutingTransaction recordTx(DoubleEntryTransaction tx, int neededVotes) {
        final String txAsString;
        try {
            txAsString = objectMapper.writeValueAsString(tx);
        } catch (JsonProcessingException e) {
            throw new MessagePrepFailedException(e);
        }

        return execTxRepo.save(
            new ExecutingTransaction(
                tx.transactionId(),
                txAsString,
                /* We voted yes. */
                1,
                neededVotes,
                true
            )
        );
    }

    /**
     * Precondition: the message has no local processing left to do.
     *
     * <p>
     * If {@code destinations} contains our bank ID, it will be ignored. If {@code destinations}
     * must not be only our bank.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    protected void queueOutgoingMessage(Message message, Set<Long> destinations) {
        final String messageAsString;
        try {
            messageAsString = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new MessagePrepFailedException(e);
        }

        var didSend = false;
        for (final var dest : destinations) {
            if (dest == ForeignBankId.OUR_ROUTING_NUMBER) continue;
            didSend = true;
            outboxRepo.save(
                new OutboxMessage(
                    new OutboxMessageId(message.idempotenceKey(), dest),
                    messageAsString,
                    false,
                    Instant.now()
                        .minus(
                            interbankConfig.getResendDuration()
                                .multipliedBy(2)
                        )
                )
            );
        }
        if (!didSend)
            /* Probably forgot to do something. */
            throw new IllegalStateException("Didn't properly handle a lack of local message?");
    }

    private sealed interface ResolvePersonMonetaryAssetPostingResult {
        record Success(Posting posting) implements ResolvePersonMonetaryAssetPostingResult {
        }

        record Failed(NoVoteReason reason) implements ResolvePersonMonetaryAssetPostingResult {
        }
    }

    /**
     * Replaces postings to {@link TxAccount.Person} with postings to {@link TxAccount.Account}. The
     * accounts picked are accounts that can be credited and debited in the given currency, and have
     * sufficient funds (in the case of credits). If no such account exists, a failure is indicated.
     * If a non-{@link TxAccount.Person Person} posting is passed, it is returned unaltered.
     *
     * @param posting Posting to resolve
     * @return A posting, if a person, it will be replaced by an account.
     */
    private ResolvePersonMonetaryAssetPostingResult resolvePersonMonetaryAssetPosting(
        Posting posting
    ) {
        if (
            !(posting.account() instanceof TxAccount.Person person)
                || posting.account()
                    .routingNumber()
                    != ForeignBankId.OUR_ROUTING_NUMBER
                || !(posting
                    .asset() instanceof TxAsset.Monas(MonetaryAsset(CurrencyCode currencyCode)))
        ) return new ResolvePersonMonetaryAssetPostingResult.Success(posting);
        final var userId =
            UUID.fromString(
                person.id()
                    .id()
            );
        return accountService.getRequiredAccount(
            userId,
            currencyCode,
            posting.amount()
                .negate()
                .max(BigDecimal.ZERO)
        )
            .map(x -> posting.withAccount(new TxAccount.Account(x.accountNumber())))
            .<ResolvePersonMonetaryAssetPostingResult>map(
                ResolvePersonMonetaryAssetPostingResult.Success::new
            )
            .orElseGet(
                () -> new ResolvePersonMonetaryAssetPostingResult.Failed(
                    posting.amount()
                        .compareTo(BigDecimal.ZERO)
                        == 0
                            ? new NoVoteReason.UnacceptableAsset(posting)
                            : new NoVoteReason.InsufficientAsset(posting)
                )
            );
    }

    private DoubleEntryTransaction preprocessDoubleEntryTx(DoubleEntryTransaction tx) {
        final var postings =
            tx.postings()
                .stream()
                .map(this::resolvePersonMonetaryAssetPosting)
                .toList();
        final var reasonsAgainst =
            postings.stream()
                .filter(x -> x instanceof ResolvePersonMonetaryAssetPostingResult.Failed)
                .map(x -> ((ResolvePersonMonetaryAssetPostingResult.Failed) x).reason())
                .toList();
        if (!reasonsAgainst.isEmpty()) throw new TxLocalPartVotedNo(tx, reasonsAgainst);
        return tx.withPostings(
            postings.stream()
                .map(x -> ((ResolvePersonMonetaryAssetPostingResult.Success) x).posting())
                .toList()
        );
    }

    @Override
    public ForeignBankId submitTx(final DoubleEntryTransaction tx_) {
        log.debug("IBEX taking tx {}", tx_);
        final var destinations = collectAndValidateDestinations(tx_);
        if (!destinations.contains(ForeignBankId.OUR_ROUTING_NUMBER))
            throw new IllegalArgumentException("Transaction is not in our bank");
        final var tx =
            preprocessDoubleEntryTx(tx_).withTransactionId(ForeignBankId.our(UUID.randomUUID()));

        synchronized (transactionKey) {
            /* Needs to be synced due to he use of executeLocalPhase1. */
            txTemplate.executeWithoutResult(status -> {
                executeLocalPhase1(tx);

                /* We voted yes. */
                final var idempotenceKey = newIdempotenceKey();
                final var recordedTx = recordTx(tx, destinations.size());

                /* Are we the only ones? */
                if (destinations.equals(Set.of(ForeignBankId.OUR_ROUTING_NUMBER))) {
                    /* Yes. Finish the TX. */
                    assert recordedTx.isVotesAreYes()
                        && recordedTx.getVotesCast() == 1
                        && recordedTx.getNeededVotes() == 1
                           : "Expected new transaction to be complete (there's only one destination)";
                    executeLocalPhase2(tx);
                } else {
                    /* No. Tell the others. */
                    assert recordedTx.getVotesCast() == 1 && recordedTx.getNeededVotes() > 1;
                    queueOutgoingMessage(new Message.NewTx(idempotenceKey, tx), destinations);
                }
            });
        }

        processOutbox();

        return tx.transactionId();
    }

    @Override
    @Transactional(
        /* does this make sense even? */ isolation = Isolation.SERIALIZABLE
    )
    public ForeignBankId submitImmediateTx(final DoubleEntryTransaction tx_) {
        log.debug("IBEX taking tx {}", tx_);
        final var destinations = collectAndValidateDestinations(tx_);
        if (!destinations.contains(ForeignBankId.OUR_ROUTING_NUMBER))
            throw new IllegalArgumentException("Transaction is not in our bank");
        if (destinations.size() != 1)
            throw new IllegalArgumentException("Transaction is not fully in our bank");
        final var tx =
            preprocessDoubleEntryTx(tx_).withTransactionId(ForeignBankId.our(UUID.randomUUID()));

        synchronized (transactionKey) {
            executeLocalPhase1(tx);

            recordTx(tx, 1);

            executeLocalPhase2(tx);

            return tx.transactionId();
        }
    }

    /* =========================== User-facing TX status updates. =========================== */
    /**
     * For each user-facing transaction object (see {@link Transaction}) that is not yet {@code
     * REALIZED} or {@code REJECTED}, update its status to match the status of its executing
     * transaction. This fixes a race in which an executing transaction may commit faster than a
     * user-facing transaction is created.
     */
    @Transactional
    @Scheduled(
        fixedDelay = 30,
        timeUnit = TimeUnit.SECONDS
    )
    public void updateStaleTxStatuses() {
        execTxRepo.updateStaleTxStatuses();
    }

    /* =============================== Inter-bank processing. =============================== */

    /* Synchronization key for the outbox. We don't want multiple outboxings at once. */
    private final Object messageSendKey = new Object();

    @Async("txExecutorPool")
    protected void processOutbox() {
        synchronized (messageSendKey) {
            final List<Pair<OutboxMessageId, String>> toResend;
            synchronized (transactionKey) {
                final var now = Instant.now();
                final var lastSendInstant = now.minus(interbankConfig.getResendDuration());
                toResend =
                    txTemplate.execute(
                        new TransactionCallback<List<Pair<OutboxMessageId, String>>>() {
                            @Override
                            public List<Pair<OutboxMessageId, String>> doInTransaction(
                                TransactionStatus status
                            ) {
                                final var messages = outboxRepo.findAllSentBefore(lastSendInstant);
                                messages.forEach(m -> m.setLastSendTime(now));
                                outboxRepo.saveAll(messages);
                                return messages.stream()
                                    .map(m -> Pair.of(m.getMessageKey(), m.getMessageBody()))
                                    .toList();
                            }
                        }
                    );
            }

            log.trace("need to resend {}", toResend);
            for (final var msg : toResend) {
                try {
                    final var message = objectMapper.readValue(msg.getRight(), Message.class);
                    sendStoredMessage(msg.getLeft(), message);
                } catch (IOException e) {
                    log.error("cannot deliver message {}: {}", msg, e);
                }
            }
        }
    }

    private void sendStoredMessage(OutboxMessageId msgId, Message particularMessage)
        throws IOException {
        final var remote = interbanks.get(msgId.destination());

        log.trace("sending message to bank {}: {}", msgId.destination(), particularMessage);
        final var response = (switch (particularMessage) {
        case Message.NewTx newTx -> remote.sendNewTx(newTx);
        case Message.CommitTx commitTx -> remote.sendCommit(commitTx);
        case Message.RollbackTx rollbackTx -> remote.sendRollback(rollbackTx);
        }).execute();
        log.trace("received response from bank {}: {}", msgId.destination(), response);

        if (response.code() == 202) {
            /* No response yet. The DB was already updated with the next resend time. */
            return;
        }

        if (!response.isSuccessful()) {
            /* Didn't manage to deliver. */
            log.error("Failed to deliver message {}: {}", msgId, response);
            return;
        }

        txTemplate.executeWithoutResult(status -> {
            if (response.body() instanceof TransactionVote txVote) {
                processVote(((Message.NewTx) particularMessage).message(), txVote);
            }
            outboxRepo.markAsDelivered(msgId);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    protected void processVote(DoubleEntryTransaction tx, TransactionVote txVote) {
        final var ongoingTx =
            execTxRepo.findById(tx.transactionId())
                .orElseThrow(() -> new IllegalStateException("tx we sent vanished?"));

        ongoingTx.setVotesCast(ongoingTx.getVotesCast() + 1);

        if (txVote instanceof TransactionVote.No noVote) {
            /* This party voted no. */
            ongoingTx.setVotesAreYes(false);
            /* TODO(arsen): process reason */
            log.error("tx {} failed to execute due to {}", tx, noVote);
        }

        /*
         * If we just received the last vote, we should queue out the commits and rollbacks, as well
         * as perform our part of the commit.
         */

        if (ongoingTx.getNeededVotes() == ongoingTx.getVotesCast()) {
            if (ongoingTx.isVotesAreYes()) {
                /* Success. */
                executeLocalPhase2(tx);
                final var idempotenceKey = newIdempotenceKey();
                queueOutgoingMessage(
                    new Message.CommitTx(idempotenceKey, new CommitTransaction(tx.transactionId())),
                    collectAndValidateDestinations(tx)
                );
            } else {
                /* Someone said no. Roll back everyone. */
                final var idempotenceKey = newIdempotenceKey();
                rollbackLocalPhase1(tx);
                queueOutgoingMessage(
                    new Message.RollbackTx(
                        idempotenceKey,
                        new RollbackTransaction(tx.transactionId())
                    ),
                    collectAndValidateDestinations(tx)
                );
            }
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.debug("Starting IBEX with config {}", interbankConfig);
        taskScheduler.scheduleAtFixedRate(
            () -> processOutbox(),
            interbankConfig.getResendDuration()
                .dividedBy(2)
        );
    }

    /* Message reception. */
    protected <T, M extends Message> T doIdempotentMessageHandling(
        M msg,
        Class<T> responseType,
        Function<M, T> handler
    ) {
        log.trace("received remote message: {}", msg);
        final var idemKey = msg.idempotenceKey();
        final var isVoid = responseType.equals(Void.TYPE);
        synchronized (transactionKey) {
            return txTemplate.execute(new TransactionCallback<T>() {
                @Override
                @SneakyThrows
                public T doInTransaction(TransactionStatus status) {
                    final var prevMsg = inboxRepo.findById(idemKey);
                    if (prevMsg.isPresent()) {
                        /* Previously-handled message. */
                        final var oldResp =
                            prevMsg.get()
                                .getResponseBody();
                        assert isVoid == (oldResp == null)
                               : "Body must be null iff response type is void";

                        if (isVoid)
                            /* It's void - simple case. */
                            return null;
                        return objectMapper.readValue(oldResp, responseType);
                    } else {
                        final var resp = handler.apply(msg);
                        inboxRepo.save(
                            new InboxMessage(
                                idemKey,
                                isVoid ? null : objectMapper.writeValueAsString(resp)
                            )
                        );
                        return resp;
                    }
                }
            });
        }
    }

    /**
     * This function is a hack that uses a heuristic described below to produce user-facing
     * transaction objects (i.e. {@link Transaction}) for incoming transactions.
     *
     * <p>
     * The heuristic is as follows: if {@code tx} consists of exactly two postings of monetary
     * amounts to two accounts, then a transaction is produced from the account in the posting with
     * a negative amount to the account with a posting of the positive amount.
     *
     * <p>
     * This function assumes balanced transactions preprocessed with
     * {@link #preprocessDoubleEntryTx(DoubleEntryTransaction)}.
     *
     * <p>
     * Saves user-facing transactions as a side-effect.
     *
     * @param tx The 2E transaction to apply this heuristic to.
     */
    private void recordHeuristicIncomingTransaction(DoubleEntryTransaction tx) {
        if (
            tx.postings()
                .size()
                != 2
        ) return;
        if (
            !tx.postings()
                .stream()
                .map(Posting::asset)
                .allMatch(TxAsset.Monas.class::isInstance)
        ) return;
        if (
            !tx.postings()
                .stream()
                .map(Posting::account)
                .allMatch(TxAccount.Account.class::isInstance)
        ) return;

        final var send =
            tx.postings()
                .stream()
                .min(Comparator.comparing(Posting::amount))
                .orElseThrow(AssertionError::new);
        final var receive =
            tx.postings()
                .stream()
                .max(Comparator.comparing(Posting::amount))
                .orElseThrow(AssertionError::new);
        userFacingTxRepo.save(
            new Transaction(
                UUID.randomUUID(),
                "%s-%s".formatted(
                    tx.transactionId()
                        .routingNumber(),
                    tx.transactionId()
                        .id()
                ),
                ((TxAccount.Account) send.account()).num(),
                ((TxAccount.Account) receive.account()).num(),
                send.amountAsMonetaryValue(),
                receive.amountAsMonetaryValue(),
                /* TODO(arsen): this is a placeholder. */
                new MonetaryAmount(BigDecimal.ZERO, CurrencyCode.RSD),
                /* TODO(arsen): what is a recipient? why is there no javadoc? */
                ((TxAccount.Account) receive.account()).num(),
                "",
                "",
                tx.message(),
                LocalDateTime.now(),
                rs.banka4.bank_service.domain.transaction.db.TransactionStatus.IN_PROGRESS,
                /* Always between people. */
                false,
                tx.transactionId()
            )
        );
    }

    public TransactionVote processNewTxMessage(Message.NewTx message) {
        try {
            return doIdempotentMessageHandling(message, TransactionVote.class, m -> {
                final var tx = preprocessDoubleEntryTx(message.message());
                recordTx(tx, 2);
                executeLocalPhase1(tx);
                recordHeuristicIncomingTransaction(tx);
                return new TransactionVote.Yes();
            });
        } catch (TxLocalPartVotedNo reason) {
            return doIdempotentMessageHandling(message, TransactionVote.class, m -> {
                final var tx = message.message();
                final var ectx = recordTx(tx, 2);
                ectx.setVotesAreYes(false);
                execTxRepo.save(ectx);
                recordHeuristicIncomingTransaction(tx);
                return new TransactionVote.No(reason.getReasons());
            });
        }
    }

    public void processCommitOrRollbackMessage(Message message) {
        final var isCommit = switch (message) {
        case Message.CommitTx ignored -> true;
        case Message.RollbackTx ignored -> false;
        default -> throw new IllegalArgumentException();
        };
        final var txId = switch (message) {
        /* @formatter:off */
        case Message.CommitTx m -> m.message().transactionId();
        case Message.RollbackTx m -> m.message().transactionId();
        default -> throw new AssertionError("unreachable, but javac is being a silly billy :(");
        /* @formatter:on */
        };
        doIdempotentMessageHandling(message, TransactionVote.class, m -> {
            final var tx =
                execTxRepo.findById(txId)
                    .orElseThrow(() -> new IllegalStateException("Invalid tx?"));
            try {
                final var fullTx =
                    objectMapper.readValue(tx.getTxObject(), DoubleEntryTransaction.class);

                if (isCommit) executeLocalPhase2(fullTx);
                else if (tx.isVotesAreYes()) rollbackLocalPhase1(fullTx);

                assert tx.isVotesAreYes() || !isCommit
                       : "Votes-are-no -> Votes-are-yes edge invalid";

                tx.setVotesCast(tx.getNeededVotes());
                tx.setVotesAreYes(isCommit);
                execTxRepo.save(tx);
                return null;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid TX was persisted?", e);
            }
        });
    }

    public List<Posting> ensurePostingCurrency(
        Posting posting,
        CurrencyCode targetCurrencyCode,
        TxAccount.Account targetAccount
    ) {
        if (posting.asset() instanceof TxAsset.Monas(MonetaryAsset asset)) {
            if (
                asset.currency()
                    .equals(targetCurrencyCode)
            ) {
                return handleSameToSameTx(posting, targetAccount);
            } else
                if (
                    asset.currency() != CurrencyCode.RSD && targetCurrencyCode != CurrencyCode.RSD
                ) {
                    return handleForeignToForeignTx(posting, targetCurrencyCode, targetAccount);
                } else {
                    return handleForeignAndRsdTx(posting, targetCurrencyCode, targetAccount);
                }
        }
        throw new InvalidTxAsset();
    }

    private List<Posting> handleSameToSameTx(Posting posting, TxAccount.Account targetAccount) {
        Posting fromClient =
            new Posting(
                posting.account(),
                posting.amount()
                    .negate(),
                posting.asset()
            );

        Posting toClient =
            new Posting(
                new TxAccount.Account(targetAccount.num()),
                posting.amount(),
                posting.asset()
            );

        return List.of(fromClient, toClient);
    }

    private List<Posting> handleForeignToForeignTx(
        Posting posting,
        CurrencyCode targetCurrencyCode,
        TxAccount.Account targetAccount
    ) {
        List<Posting> postings = new ArrayList<>();

        if (posting.asset() instanceof TxAsset.Monas(MonetaryAsset asset)) {
            Account bankAccountFrom =
                bankAccountService.getBankAccountForCurrency(asset.currency());
            Account bankAccountTo =
                bankAccountService.getBankAccountForCurrency(targetCurrencyCode);

            postings.addAll(createFeePostings(posting, bankAccountFrom));

            Posting removeFromClient =
                new Posting(
                    posting.account(),
                    posting.amount()
                        .negate(),
                    posting.asset()
                );

            Posting addToBankAccountFrom =
                new Posting(
                    new TxAccount.Account(bankAccountFrom.getAccountNumber()),
                    posting.amount(),
                    posting.asset()
                );

            BigDecimal convertedAmountToRsd =
                exchangeRateService.convertCurrency(
                    addToBankAccountFrom.amount(),
                    asset.currency(),
                    CurrencyCode.RSD
                );

            BigDecimal convertedAmountToSpecificForeignCurrency =
                exchangeRateService.convertCurrency(
                    convertedAmountToRsd,
                    CurrencyCode.RSD,
                    targetCurrencyCode
                );

            Posting removeFromBankTo =
                new Posting(
                    new TxAccount.Account(bankAccountTo.getAccountNumber()),
                    convertedAmountToSpecificForeignCurrency.negate(),
                    new TxAsset.Monas(targetCurrencyCode)
                );

            Posting addToTargetClient =
                new Posting(
                    new TxAccount.Account(targetAccount.num()),
                    convertedAmountToSpecificForeignCurrency,
                    new TxAsset.Monas(targetCurrencyCode)
                );

            postings.addAll(
                List.of(removeFromClient, addToBankAccountFrom, removeFromBankTo, addToTargetClient)
            );
        }

        return postings;
    }

    private List<Posting> handleForeignAndRsdTx(
        Posting posting,
        CurrencyCode targetCurrency,
        TxAccount.Account targetAccount
    ) {

        List<Posting> postings = new ArrayList<>();

        if (posting.asset() instanceof TxAsset.Monas(MonetaryAsset asset)) {
            Account bankAccountFrom =
                bankAccountService.getBankAccountForCurrency(asset.currency());
            Account bankAccountTo = bankAccountService.getBankAccountForCurrency(targetCurrency);

            postings.addAll(createFeePostings(posting, bankAccountFrom));

            Posting removeFromClient =
                new Posting(
                    posting.account(),
                    posting.amount()
                        .negate(),
                    posting.asset()
                );

            Posting addToBankAccountFrom =
                new Posting(
                    new TxAccount.Account(bankAccountFrom.getAccountNumber()),
                    posting.amount(),
                    posting.asset()
                );

            BigDecimal convertedAmount =
                exchangeRateService.convertCurrency(
                    posting.amount(),
                    asset.currency(),
                    targetCurrency
                );

            Posting removeFromBankTo =
                new Posting(
                    new TxAccount.Account(bankAccountTo.getAccountNumber()),
                    convertedAmount.negate(),
                    new TxAsset.Monas(targetCurrency)
                );

            Posting addToTargetClient =
                new Posting(
                    new TxAccount.Account(targetAccount.num()),
                    convertedAmount,
                    new TxAsset.Monas(targetCurrency)
                );

            postings.addAll(List.of(removeFromClient, addToBankAccountFrom));
            postings.addAll(List.of(removeFromBankTo, addToTargetClient));
        }

        return postings;
    }

    /**
     * Creates a list of postings to represent the fee charged for a transaction.
     * <p>
     * This method generates two postings:
     * <ul>
     * <li>A posting that debits the fee amount from the client's account.</li>
     * <li>A posting that credits the fee amount to the bank's account.</li>
     * </ul>
     *
     * @param posting The original transaction posting.
     * @param bankAccountFrom The bank account.
     * @return A list of postings representing the fee transaction.
     */
    private List<Posting> createFeePostings(Posting posting, Account bankAccountFrom) {
        BigDecimal fee =
            exchangeRateService.calculateFee(posting.amount())
                .multiply(BigDecimal.TWO);

        Posting feePostingClient = new Posting(posting.account(), fee.negate(), posting.asset());

        Posting feePostingBank =
            new Posting(
                new TxAccount.Account(bankAccountFrom.getAccountNumber()),
                fee,
                posting.asset()
            );

        return List.of(feePostingClient, feePostingBank);
    }

}
