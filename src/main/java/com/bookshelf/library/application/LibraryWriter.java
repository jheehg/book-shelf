package com.bookshelf.library.application;

import com.bookshelf.library.LibraryInfo;
import com.bookshelf.library.Region;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class LibraryWriter {

    private final JdbcClient jdbc;

    public LibraryWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 쪽(최대 500곳)을 한 트랜잭션에 */
    @Transactional
    public void upsert(Region region, List<LibraryInfo> libraries, Instant now) {
        for (LibraryInfo l : libraries) {
            jdbc.sql("""
                    INSERT INTO library (lib_code, name, address, region_code, tel, latitude, longitude, homepage, synced_at)
                    VALUES (:code, :name, :address, :region, :tel, :lat, :lng, :home, :now)
                    ON CONFLICT (lib_code) DO UPDATE SET
                        name = EXCLUDED.name, address = EXCLUDED.address, region_code = EXCLUDED.region_code,
                        tel = EXCLUDED.tel, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                        homepage = EXCLUDED.homepage, synced_at = EXCLUDED.synced_at
                    """)
                    .param("code", l.libCode())
                    .param("name", l.name())
                    .param("address", l.address())
                    .param("region", region.code())
                    .param("tel", l.tel())
                    .param("lat", l.latitude())
                    .param("lng", l.longitude())
                    .param("home", l.homepage())
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    public void finishRun(Instant now, int libraries) {
        jdbc.sql("INSERT INTO library_sync_run (finished_at, libraries) VALUES (:now, :n)")
                .param("now", Timestamp.from(now)).param("n", libraries).update();
    }

    public Optional<Instant> lastFinishedAt() {
        return jdbc.sql("SELECT finished_at FROM library_sync_run ORDER BY finished_at DESC LIMIT 1")
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    public boolean exists(String libCode) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM library WHERE lib_code = :c)")
                .param("c", libCode).query(Boolean.class).single();
    }
}
