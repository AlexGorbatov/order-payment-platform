package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An amount of money in minor units (cents for EUR)")
public record MoneyResponse(
        @Schema(example = "2597") long amountMinor,

        @Schema(description = "ISO 4217 code", example = "EUR")
        String currency) {

    static MoneyResponse of(Money money) {
        return new MoneyResponse(money.amountMinor(), money.currencyCode());
    }
}
