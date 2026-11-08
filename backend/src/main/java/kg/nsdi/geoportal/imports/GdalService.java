package kg.nsdi.geoportal.imports;

import kg.nsdi.geoportal.common.ApiException;
import kg.nsdi.geoportal.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Обёртка над GDAL/OGR (ЧТЗ п. 3.5): чтение форматов Shapefile (zip), GeoPackage, GeoJSON, KML/KMZ,
 * MapInfo TAB/MIF, File Geodatabase (zip), CSV с координатами; перепроецирование из Kyrg-06 / ITRF-2005 / WGS-84.
 */
@Service
public class GdalService {
    private final AppProperties props;

    public GdalService(AppProperties props) { this.props = props; }

    /** Путь, который понимает GDAL (архивы открываются через /vsizip/). */
    public String gdalPath(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String abs = file.toAbsolutePath().toString();
        return name.endsWith(".zip") ? "/vsizip/" + abs : abs;
    }

    private List<String> openOptions(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return List.of("-oo", "X_POSSIBLE_NAMES=lon*,lng,long,x,долгота", "-oo", "Y_POSSIBLE_NAMES=lat*,y,широта",
                           "-oo", "KEEP_GEOM_COLUMNS=NO", "-oo", "AUTODETECT_TYPE=YES");
        }
        return List.of();
    }

    /** ogrinfo -json -so: структура файла (слои, поля, СК, число объектов). */
    public String info(Path file) {
        List<String> cmd = new ArrayList<>(List.of(props.gdal().ogrinfo(), "-json", "-so", "-ro"));
        cmd.addAll(openOptions(file));
        cmd.add(gdalPath(file));
        return run(cmd, "Не удалось прочитать файл: формат не поддерживается или файл повреждён");
    }

    /** Загрузка слоя файла в staging-таблицу PostGIS с трансформацией в EPSG:4326. */
    public void loadToStaging(Path file, String ogrLayer, String table, int sourceSrid) {
        List<String> cmd = new ArrayList<>(List.of(props.gdal().ogr2ogr(), "-f", "PostgreSQL", "PG:" + props.gdal().pg()));
        cmd.addAll(openOptions(file));
        cmd.add(gdalPath(file));
        if (ogrLayer != null) cmd.add(ogrLayer);
        cmd.addAll(List.of("-nln", "staging." + table, "-overwrite",
                "-lco", "GEOMETRY_NAME=geom", "-lco", "FID=ogc_fid", "-lco", "SPATIAL_INDEX=NONE",
                "-lco", "LAUNDER=NO", "-lco", "PRECISION=NO",
                "-nlt", "GEOMETRY", "-dim", "XY",
                "-s_srs", "EPSG:" + sourceSrid, "-t_srs", "EPSG:4326"));
        run(cmd, "Ошибка загрузки данных");
    }

    private String run(List<String> cmd, String errorPrefix) {
        try {
            Path errFile = Files.createTempFile("gdal", ".err");
            Process p = new ProcessBuilder(cmd).redirectError(errFile.toFile()).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(props.gdal().timeoutSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw ApiException.bad(errorPrefix + ": превышено время обработки");
            }
            byte[] err = Files.readAllBytes(errFile);
            Files.deleteIfExists(errFile);
            if (p.exitValue() != 0) {
                String msg = new String(err, StandardCharsets.UTF_8).lines().filter(l -> l.contains("ERROR")).findFirst()
                        .orElse(new String(err, StandardCharsets.UTF_8)).replace(props.gdal().pg(), "PG:***");
                throw ApiException.bad(errorPrefix + ": " + msg.trim());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("GDAL недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
