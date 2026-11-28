-- Auto-pay settles newly issued bills for accounts that opted in, recorded with its own payment method.
ALTER TABLE payment_transactions DROP CONSTRAINT payment_transactions_payment_method_check;
ALTER TABLE payment_transactions ADD CONSTRAINT payment_transactions_payment_method_check
    CHECK (payment_method IN ('UPI','CREDIT_CARD','DEBIT_CARD','NET_BANKING','AUTO_PAY'));
