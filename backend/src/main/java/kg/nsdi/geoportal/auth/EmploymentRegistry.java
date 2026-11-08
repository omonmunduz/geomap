package kg.nsdi.geoportal.auth;

import java.util.List;
import java.util.Optional;

/**
 * Источник кадровых сведений сотрудника (АИС «Е-Кызмат», ЧТЗ п. 5.4.3):
 * организация, должность, статус занятости is_active.
 * В MVP — {@link MockEmploymentRegistry}; в продуктиве — REST-клиент Е-Кызмат через «Түндүк».
 */
public interface EmploymentRegistry {

    record Employment(String subject, String displayName, String email, String orgCode, String position,
                      boolean active, boolean bootstrapAdmin, String provider) {}

    Optional<Employment> lookup(String subject);

    /** Только для демонстрационного входа: перечень тестовых учётных записей. */
    List<Employment> demoAccounts();
}
