package com.example.Project1.Model.Entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.List;
import java.util.UUID;

@Entity(name = "Dentist")
@Table(name = "dentists")
public class Dentist {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID dentistUUID;
    private String dentistLicenceNumber;
    private String dentistName;
    private String dentistLastname;
    private String dentistEmail;
    private String dentistPhone;
    private boolean isDentistActive;

    @JsonIgnore
    @OneToMany(mappedBy = "dentist")
    private List<MessageThread> dentistMessageThreadList;

    @JsonIgnore
    @OneToMany(mappedBy = "dentistAppointment")
    private List<Appointment> dentistAppointmentList;

    public Dentist() {
    }

    public Dentist(UUID dentistUUID, String dentistLicenceNumber, String dentistName, String dentistLastname,
                   String dentistEmail, String dentistPhone, boolean isDentistActive) {
        this.dentistUUID = dentistUUID;
        this.dentistLicenceNumber = dentistLicenceNumber;
        this.dentistName = dentistName;
        this.dentistLastname = dentistLastname;
        this.dentistEmail = dentistEmail;
        this.dentistPhone = dentistPhone;
        this.isDentistActive = isDentistActive;
    }

    public UUID getDentistUUID() {
        return dentistUUID;
    }

    public void setDentistUUID(UUID dentistUUID) {
        this.dentistUUID = dentistUUID;
    }

    public String getDentistLicenceNumber() {
        return dentistLicenceNumber;
    }

    public void setDentistLicenceNumber(String dentistLicenceNumber) {
        this.dentistLicenceNumber = dentistLicenceNumber;
    }

    public String getDentistName() {
        return dentistName;
    }

    public void setDentistName(String dentistName) {
        this.dentistName = dentistName;
    }

    public String getDentistLastname() {
        return dentistLastname;
    }

    public void setDentistLastname(String dentistLastname) {
        this.dentistLastname = dentistLastname;
    }

    public String getDentistEmail() {
        return dentistEmail;
    }

    public void setDentistEmail(String dentistEmail) {
        this.dentistEmail = dentistEmail;
    }

    public String getDentistPhone() {
        return dentistPhone;
    }

    public void setDentistPhone(String dentistPhone) {
        this.dentistPhone = dentistPhone;
    }

    public boolean isDentistActive() {
        return isDentistActive;
    }

    public void setDentistActive(boolean dentistActive) {
        isDentistActive = dentistActive;
    }

    public List<MessageThread> getDentistMessageThreadList() {
        return dentistMessageThreadList;
    }

    public void setDentistMessageThreadList(List<MessageThread> dentistMessageThreadList) {
        this.dentistMessageThreadList = dentistMessageThreadList;
    }

    public List<Appointment> getDentistAppointmentList() {
        return dentistAppointmentList;
    }

    public void setDentistAppointmentList(List<Appointment> dentistAppointmentList) {
        this.dentistAppointmentList = dentistAppointmentList;
    }
}
