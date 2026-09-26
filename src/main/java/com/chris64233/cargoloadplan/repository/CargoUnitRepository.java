package com.chris64233.cargoloadplan.repository;

import com.chris64233.cargoloadplan.domain.CargoUnit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CargoUnitRepository extends JpaRepository<CargoUnit, Long> {

    Optional<CargoUnit> findByUnitNo(String unitNo);

    /**
     * 按 id 排序加悲观写锁，避免并发方案争抢同一货物时出现脏分配，
     * 固定排序避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CargoUnit c where c.id in :ids order by c.id")
    List<CargoUnit> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
