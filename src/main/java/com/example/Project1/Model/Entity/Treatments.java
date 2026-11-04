package com.example.Project1.Model.Entity;

import jakarta.persistence.*;

@Entity(name = "Treatments")
@Table(name = "treatments")
public class Treatments {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "treatments",nullable = false)
    private String treatment;

    private long price;

    // Default no-args constructor required by JPA
    public Treatments() {
    }

    public Treatments(Long id, String treatment, long price) {
        this.id = id;
        this.treatment = treatment;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTreatment() {
        return treatment;
    }

    public void setTreatment(String treatment) {
        this.treatment = treatment;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }
}
