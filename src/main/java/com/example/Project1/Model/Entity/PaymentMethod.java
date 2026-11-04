package com.example.Project1.Model.Entity;

public enum PaymentMethod {
    CASH("EFECTIVO"),
    TRANSFER("TRANSFERENCIA");

    private final String paymentMethod;

    PaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }
}
