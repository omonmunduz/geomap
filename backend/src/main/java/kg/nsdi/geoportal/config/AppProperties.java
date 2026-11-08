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

    public record Seed(boolean enabled, String dir) {}
}
