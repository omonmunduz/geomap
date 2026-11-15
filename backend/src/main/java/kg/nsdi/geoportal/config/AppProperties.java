package kg.nsdi.geoportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        int jwtTtlMinutes,
        boolean devLogin,
        String storageDir,
        Gdal gdal,
        Seed seed) {

    public record Gdal(String ogr2ogr, String ogrinfo, String pg, int timeoutSeconds) {}

    /** zoningCheck — запускать ли начальную проверку зонирования по Бишкеку после наполнения (SEED_ZONING_CHECK). */
    public record Seed(boolean enabled, String dir, boolean zoningCheck) {}
}
