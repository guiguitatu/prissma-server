package br.pucpr.prissma_server.design;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnvironmentPreviewRepository extends JpaRepository<EnvironmentPreview, Long> {

    Optional<EnvironmentPreview> findByIdAndProposalId(Long id, Long proposalId);

    /**
     * Só as chaves das imagens de entrada, sem materializar as prévias — mesmo
     * motivo de {@code DesignSubmissionRepository#findFileUrlsByProposal}.
     *
     * A foto do ambiente e a planta ficam no FileStorageService e não viram
     * attachments da obra: se a exclusão da proposta não as apagar, ninguém
     * apaga.
     */
    @Query("SELECT p.rawImageKey FROM EnvironmentPreview p WHERE p.proposal.id = :proposalId")
    List<String> findRawImageKeysByProposal(@Param("proposalId") Long proposalId);

    @Query("SELECT p.floorPlanKey FROM EnvironmentPreview p "
            + "WHERE p.proposal.id = :proposalId AND p.floorPlanKey IS NOT NULL")
    List<String> findFloorPlanKeysByProposal(@Param("proposalId") Long proposalId);

    /**
     * Mesmo motivo do {@code DesignSubmissionRepository#deleteByProposalId}: as
     * previas precisam sair pelo persistence context, senao sobram gerenciadas
     * apontando para uma proposta ja removida e o flush do commit quebra.
     */
    void deleteByProposalId(Long proposalId);
}
