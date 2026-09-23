package com.orderflow.inventory.stock.domain;

public class StockBelowReservedException extends RuntimeException {

    public StockBelowReservedException(int onHand, int reserved) {
        super("onHand " + onHand + " is below the " + reserved + " units already reserved");
    }
}
