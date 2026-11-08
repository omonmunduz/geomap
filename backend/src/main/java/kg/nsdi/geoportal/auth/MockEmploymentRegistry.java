package kg.nsdi.geoportal.auth;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Заглушка Е-Кызмат + Infocom ID + Түндүк для демонстрации (вымышленные лица). */
@Component
public class MockEmploymentRegistry implements EmploymentRegistry {

    private static final List<Employment> ACCOUNTS = List.of(
        new Employment("infocom:admin",    "Администратор Геопортала",  "admin@demo.kg",    "zemagency", "Администратор системы", true, true, "infocom"),
        new Employment("infocom:goskart",  "Асель Токтогулова",          "goskart@demo.kg",  "goskart",   "Ведущий специалист отдела картографии", true, false, "infocom"),
        new Employment("infocom:zem",      "Нурлан Асанов",              "zem@demo.kg",      "zemagency", "Главный специалист отдела кадастра", true, false, "infocom"),
        new Employment("infocom:minarch",  "Айгерим Садыкова",           "arch@demo.kg",     "minarch",   "Специалист управления градостроительства", true, false, "infocom"),
        new Employment("infocom:minzdrav", "Динара Мамытова",            "health@demo.kg",   "minzdrav",  "Специалист отдела ИТ", true, false, "infocom"),
        new Employment("infocom:mchs",     "Бакыт Осмонов",              "mchs@demo.kg",     "mchs",      "Оперативный дежурный", true, false, "infocom"),
        new Employment("infocom:former",   "Эрмек Бекмуратов",           "former@demo.kg",   "mintrans",  "Бывший специалист (is_active = false)", false, false, "infocom"),
        new Employment("tunduk:citizen",   "Азамат Гражданин",           "citizen@demo.kg",  null,        "Гражданин", true, false, "tunduk")
    );

    @Override
    public Optional<Employment> lookup(String subject) {
        return ACCOUNTS.stream().filter(a -> a.subject().equals(subject)).findFirst();
    }

    @Override
    public List<Employment> demoAccounts() { return ACCOUNTS; }
}
