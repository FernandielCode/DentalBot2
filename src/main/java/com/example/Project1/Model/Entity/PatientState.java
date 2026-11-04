package com.example.Project1.Model.Entity;

public enum PatientState {
    UNDEFINED("POR DEFINIR"),
    ON_TRACKING("EN SEGUIMIENTO"),
    CLOSED("CERRADO");

    private final String patientState;

    PatientState(String patientState) {
        this.patientState = patientState;
    }

    public String getPatientState() {
        return patientState;
    }
}
