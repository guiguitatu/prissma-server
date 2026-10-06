-- Notificacoes do usuario.
--
-- A notificacao e SEMPRE de um usuario especifico (nao de um workspace nem de
-- uma obra): quem le e o dono da linha, e ninguem mais. Por isso user_id e NOT
-- NULL e o ON DELETE CASCADE vale aqui -- diferente de tasks/diario, que guardam
-- o nome do responsavel para sobreviver a saida do autor, notificacao nao e
-- historico da obra. Usuario apagado, notificacao some junto.
--
-- Um evento que interessa a N pessoas (entrada nova no diario) gera N linhas,
-- uma por destinatario. Fan-out na escrita e mais simples e deixa a leitura
-- trivial: "minhas notificacoes" e um unico WHERE user_id = ?.

CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL
        REFERENCES users(id) ON DELETE CASCADE,
    -- O CHECK acompanha NotificationType. Tipo novo exige migration nova, o que
    -- e proposital: o banco recusa um tipo que o front nao sabe renderizar.
    type VARCHAR(40) NOT NULL
        CHECK (type IN ('TASK_ASSIGNED', 'INVITE_ACCEPTED', 'DIARY_ENTRY_CREATED')),
    title VARCHAR(255) NOT NULL,
    message TEXT,
    -- "read" e palavra-chave em varios dialetos; is_read evita ter que citar a
    -- coluna toda vez. O campo Java continua sendo "read".
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unica consulta de leitura do modulo: as notificacoes de um usuario, da mais
-- recente para a mais antiga. id DESC desempata linhas criadas no mesmo fan-out,
-- que compartilham o created_at ate o microssegundo.
CREATE INDEX idx_notifications_user_created
    ON notifications(user_id, created_at DESC, id DESC);

