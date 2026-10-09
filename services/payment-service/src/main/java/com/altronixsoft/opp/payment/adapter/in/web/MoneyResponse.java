package com.altronixsoft.opp.payment.adapter.in.web;

import com.altronixsoft.opp.payment.domain.Money;

/** An amount of money in minor units (cents for EUR) with its ISO 4217 currency code. */
public record MoneyResponse(long amountMinor, String currency) {

    static MoneyResponse of(Money money) {
        return new MoneyResponse(money.amountMinor(), money.currencyCode());
    }
}
