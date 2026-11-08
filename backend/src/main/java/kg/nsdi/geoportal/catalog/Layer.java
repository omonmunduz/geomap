package kg.nsdi.geoportal.catalog;

import kg.nsdi.geoportal.auth.CurrentUser;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Строка catalog.layers + правила доступа (ЧТЗ п. 5.1.1.6, таблица 19). */
public record Layer(UUID id, String code, String titleRu, String titleKy, String geomType, String accessLevel,
                   UUID ownerOrgId, boolean operational, boolean archived, String titleAttr, String styleJson,
                   Integer draftVersion, Integer publishedVersion, OffsetDateTime updatedAt) {

    public boolean canEdit(CurrentUser u) {
        return u != null && (u.isAdmin() || (u.isStaff() && ownerOrgId.equals(u.orgId())));
    }

    /** Может ли пользователь видеть опубликованную версию. */
    public boolean canViewPublished(CurrentUser u) {
        if (canEdit(u)) return true;
        if (archived || publishedVersion == null) return false;
        return "open".equals(accessLevel) || CurrentUser.staff(u);
    }

    /** Версия, которую следует показать пользователю: draft — только владельцу. */
    public Integer resolveVersion(CurrentUser u, boolean wantDraft) {
        if (wantDraft && canEdit(u) && draftVersion != null) return draftVersion;
        return canViewPublished(u) ? publishedVersion : null;
    }

    public boolean canViewVersion(CurrentUser u, int version) {
        if (canEdit(u)) return true;
        return publishedVersion != null && publishedVersion == version && canViewPublished(u);
    }
}
