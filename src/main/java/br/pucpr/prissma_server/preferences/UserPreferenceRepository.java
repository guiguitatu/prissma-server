package br.pucpr.prissma_server.preferences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);

    long deleteByUserIdAndPrefKey(Long userId, String prefKey);
}
