package com.example.Project1.repository;

import com.example.Project1.Model.DTO.SendInteractiveListRequest;
import com.example.Project1.Model.Entity.Treatments;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TreatmentsRepository extends JpaRepository<Treatments, Long> {
    Optional<Treatments> findByTreatmentIgnoreCase(String treatment);

    @Query(value = "SELECT Treatments.treatments FROM Treatments", nativeQuery = true)
    List<SendInteractiveListRequest.Row> getAllTreatmentsIgnoreCase();

    Long findPriceByTreatment(String treatment);

}
