package com.altronixsoft.opp.payment.application;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs callbacks like a transaction would: remembers whether one is open, so a test can prove where calls happen, and rolls
 * the registered stores back when the callback throws.
 */
final class FakeTransactions implements TransactionOperations {

    /** A store that can be restored to an earlier state. */
    interface Rollbackable {
        Object snapshot();

        void rollbackTo(Object snapshot);
    }

    private final List<Rollbackable> stores = new ArrayList<>();
    private boolean open;
    int started;
    int rolledBack;

    FakeTransactions manage(Rollbackable store) {
        stores.add(store);
        return this;
    }

    boolean isOpen() {
        return open;
    }

    @Override
    public <T> T execute(@NonNull TransactionCallback<T> action) {
        if (open) {
            throw new IllegalStateException("nested transaction: the worker must not nest its transactions");
        }
        open = true;
        started++;
        List<Object> snapshots = stores.stream().map(Rollbackable::snapshot).toList();
        try {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
        } catch (RuntimeException e) {
            for (int i = 0; i < stores.size(); i++) {
                stores.get(i).rollbackTo(snapshots.get(i));
            }
            rolledBack++;
            throw e;
        } finally {
            open = false;
        }
    }
}
