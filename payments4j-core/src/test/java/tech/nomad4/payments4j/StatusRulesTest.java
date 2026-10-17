package tech.nomad4.payments4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.nomad4.payments4j.PaymentStatus.*;
import static tech.nomad4.payments4j.StatusDecision.*;

class StatusRulesTest {

    @Test
    void pendingBecomesPaid() {
        assertThat(StatusRules.decide(PENDING, PAID)).isEqualTo(APPLY);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void unknownNeverChangesAnOrder(PaymentStatus current) {
        assertThat(StatusRules.decide(current, UNKNOWN)).isEqualTo(IGNORE_UNKNOWN);
    }

    @Test
    void paidIsNotRolledBackByLateEvents() {
        for (PaymentStatus late : new PaymentStatus[]{PENDING, PROCESSING, PARTIALLY_PAID, FAILED, EXPIRED, CANCELED}) {
            assertThat(StatusRules.decide(PAID, late)).as("PAID <- %s", late).isEqualTo(IGNORE_STALE);
        }
    }

    @Test
    void paidCanBeRefundedOrCharged() {
        assertThat(StatusRules.decide(PAID, REFUNDED)).isEqualTo(APPLY);
        assertThat(StatusRules.decide(PAID, CHARGEBACK)).isEqualTo(APPLY);
    }

    @Test
    void refundedStaysRefundedExceptChargeback() {
        assertThat(StatusRules.decide(REFUNDED, PAID)).isEqualTo(IGNORE_STALE);
        assertThat(StatusRules.decide(REFUNDED, PENDING)).isEqualTo(IGNORE_STALE);
        assertThat(StatusRules.decide(REFUNDED, CHARGEBACK)).isEqualTo(APPLY);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void chargebackIsFinal(PaymentStatus incoming) {
        StatusDecision expected = incoming == CHARGEBACK ? UNCHANGED : IGNORE_STALE;
        assertThat(StatusRules.decide(CHARGEBACK, incoming)).isEqualTo(expected);
    }

    @Test
    void failedExpiredAndPartiallyPaidCanRecoverToPaid() {
        assertThat(StatusRules.decide(FAILED, PAID)).isEqualTo(APPLY);
        assertThat(StatusRules.decide(EXPIRED, PAID)).isEqualTo(APPLY);
        assertThat(StatusRules.decide(PARTIALLY_PAID, PAID)).isEqualTo(APPLY);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void sameStatusIsUnchanged(PaymentStatus status) {
        assertThat(StatusRules.decide(status, status)).isEqualTo(UNCHANGED);
    }
}
