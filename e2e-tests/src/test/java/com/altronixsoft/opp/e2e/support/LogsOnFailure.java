package com.altronixsoft.opp.e2e.support;

import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/** When a scenario fails, shows the end of both service logs: the answer is usually in there, not in the assertion. */
public final class LogsOnFailure implements TestWatcher {

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Platform platform = Platform.get();
        System.err.println("==== " + context.getDisplayName() + " FAILED; service logs (last 80 lines each) ====");
        for (ServiceProcess service : new ServiceProcess[] {platform.orderService(), platform.paymentService()}) {
            System.err.println("---- " + service.name() + " (" + service.logFile() + ")");
            System.err.println(service.logTail(80));
        }
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        // nothing to show
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        // nothing to show
    }
}
