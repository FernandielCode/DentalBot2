package com.example.Project1.Model.Entity;

public enum PatientPaymentState {
    /// PAYMENT STATUS ///
    COMPLETE("COMPLETO"),
    PARTIALLY("PARCIALMENTE"),
    IN_DEBT("EN DEUDA"),
    UNDEFINED_PRICE("POR DEFINIR");
    private final String patientPaymentState;

    PatientPaymentState(String patientPaymentState) {
        this.patientPaymentState = patientPaymentState;
    }

    public String getPatientPaymentState() {
        return patientPaymentState;
    }
}
