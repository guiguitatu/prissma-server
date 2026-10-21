-- Preferências de interface por usuário: hoje o layout da Visão Geral da obra;
-- depois colunas de tabela, visões salvas etc. Genérica de propósito — uma
-- tela nova só precisa de uma chave nova (PreferenceKey), não de migration.
--
-- Excluir a conta leva as preferências junto (ON DELETE CASCADE).
CREATE TABLE user_preferences (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100) NOT NULL,
    value      JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_preferences_user_key UNIQUE (user_id, pref_key)
);
