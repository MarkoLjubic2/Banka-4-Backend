package rs.banka4.bank_service.service.impl;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import rs.banka4.bank_service.domain.security.stock.db.Stock;
import rs.banka4.bank_service.domain.trading.db.ForeignBankId;
import rs.banka4.bank_service.domain.trading.db.OtcMapper;
import rs.banka4.bank_service.domain.trading.db.OtcRequest;
import rs.banka4.bank_service.domain.trading.db.RequestStatus;
import rs.banka4.bank_service.domain.trading.db.dtos.OtcRequestCreateDto;
import rs.banka4.bank_service.domain.trading.db.dtos.OtcRequestUpdateDto;
import rs.banka4.bank_service.domain.trading.utill.BankRoutingNumber;
import rs.banka4.bank_service.exceptions.CantAcceptThisOffer;
import rs.banka4.bank_service.exceptions.OtcNotFoundException;
import rs.banka4.bank_service.exceptions.RequestFailed;
import rs.banka4.bank_service.exceptions.StockOwnershipNotFound;
import rs.banka4.bank_service.repositories.AssetOwnershipRepository;
import rs.banka4.bank_service.repositories.OtcRequestRepository;
import rs.banka4.bank_service.service.abstraction.AccountService;
import rs.banka4.bank_service.service.abstraction.OtcRequestService;
import rs.banka4.bank_service.service.abstraction.TradingService;
import rs.banka4.rafeisen.common.currency.CurrencyCode;
import rs.banka4.rafeisen.common.dto.AccountNumberDto;

@Service
@RequiredArgsConstructor
public class OtcRequestServiceImp implements OtcRequestService {
    private final OtcRequestRepository otcRequestRepository;
    private final OtcMapper otcMapper;
    private final AssetOwnershipRepository assetOwnershipRepository;
    private final TradingService tradingService;
    private final AccountService accountService;

    @Override
    public Page<OtcRequest> getMyRequests(Pageable pageable, UUID myId) {
        return otcRequestRepository.findActiveRequestsByUser(myId.toString(), pageable);
    }

    @Override
    public Page<OtcRequest> getMyRequestsUnread(Pageable pageable, UUID myId) {
        return otcRequestRepository.findActiveUnreadRequestsByUser(myId.toString(), pageable);
    }

    @Override
    public void rejectOtc(UUID requestId) {
        var otc =
            otcRequestRepository.findById(requestId)
                .orElseThrow(() -> new OtcNotFoundException(requestId));
        if (
            !otc.getStatus()
                .equals(RequestStatus.ACTIVE)
        ) throw new RequestFailed();
        otc.setStatus(RequestStatus.REJECTED);
        otcRequestRepository.save(otc);
    }

    @Override
    public void updateOtc(OtcRequestUpdateDto otcRequestUpdateDto, UUID id, UUID modifiedBy) {
        var otc =
            otcRequestRepository.findById(id)
                .orElseThrow(() -> new OtcNotFoundException(id));
        var modBy =
            new ForeignBankId(BankRoutingNumber.BANK4.getRoutingNumber(), modifiedBy.toString());
        otcMapper.update(otc, otcRequestUpdateDto, modBy);
        otcRequestRepository.save(otc);
    }

    @Override
    public void createOtc(OtcRequestCreateDto otcRequestCreateDto, UUID idMy) {
        var assetOwner =
            assetOwnershipRepository.findByMyId(
                otcRequestCreateDto.userId(),
                otcRequestCreateDto.assetId()
            )
                .orElseThrow(
                    () -> new StockOwnershipNotFound(
                        otcRequestCreateDto.userId(),
                        otcRequestCreateDto.assetId()
                    )
                );
        if (assetOwner.getPublicAmount() < otcRequestCreateDto.amount()) throw new RequestFailed();
        var me = new ForeignBankId(BankRoutingNumber.BANK4.getRoutingNumber(), idMy.toString());
        var madeFor =
            new ForeignBankId(
                BankRoutingNumber.BANK4.getRoutingNumber(),
                assetOwner.getId()
                    .getUser()
                    .getId()
                    .toString()
            );
        var stock =
            (Stock) assetOwner.getId()
                .getAsset();
        var newOtc =
            otcMapper.toOtcRequest(
                otcRequestCreateDto,
                me,
                madeFor,
                me,
                RequestStatus.ACTIVE,
                stock
            );
        otcRequestRepository.save(newOtc);
    }

    @Override
    public void acceptOtc(UUID requestId, UUID userId) {
        Optional<OtcRequest> otcRequest = otcRequestRepository.findById(requestId);
        if (otcRequest.isEmpty()) throw new OtcNotFoundException(requestId);
        OtcRequest otc = otcRequest.get();
        if (
            UUID.fromString(
                otc.getMadeBy()
                    .userId()
            )
                .equals(userId)
                || UUID.fromString(
                    otc.getMadeFor()
                        .userId()
                )
                    .equals(userId)
        ) {
            if (
                !UUID.fromString(
                    otc.getModifiedBy()
                        .userId()
                )
                    .equals(userId)
            ) {
                AccountNumberDto buyerAccount =
                    getRequiredAccount(
                        UUID.fromString(
                            otc.getMadeBy()
                                .userId()
                        ),
                        otc.getPremium()
                            .getCurrency(),
                        otc.getPremium()
                            .getAmount()
                    );
                AccountNumberDto sellerAccount =
                    getRequiredAccount(
                        UUID.fromString(
                            otc.getMadeFor()
                                .userId()
                        ),
                        otc.getPremium()
                            .getCurrency(),
                        null
                    );
                tradingService.sendPremiumAndGetOption(buyerAccount, sellerAccount, otc);
            } else {
                throw new CantAcceptThisOffer("Other side has to accept the offer", userId);
            }
        } else {
            throw new CantAcceptThisOffer("You are not in this offer", userId);
        }
    }

    public AccountNumberDto getRequiredAccount(
        UUID userId,
        CurrencyCode currencyCode,
        BigDecimal premium
    ) {
        final var accounts = accountService.getAccountsForUser(userId);
        AccountNumberDto currentAccount = null;
        AccountNumberDto rightCurrencyAccount = null;
        for (var account : accounts) {
            if (
                account.currency()
                    .equals(CurrencyCode.RSD)
            ) currentAccount = account;
            if (
                account.currency()
                    .equals(currencyCode)
            ) rightCurrencyAccount = account;
        }
        if (rightCurrencyAccount != null) {
            if (
                premium != null
                    && rightCurrencyAccount.availableBalance()
                        .compareTo(premium)
                        >= 0
            ) return rightCurrencyAccount;
            else {
                // TODO replace with insufficient funds on account exception
                throw new RequestFailed();
            }
        } else if (currentAccount != null) {
            // TODO use exchange service to determine if there is enough funds
            return currentAccount;
        } else {
            // TODO replace with no right account exception
            throw new RequestFailed();
        }
    }
}
