package kg.nsdi.geoportal.auth;

import kg.nsdi.geoportal.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AppProperties props;

    public TokenService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issue(CurrentUser u) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder c = JwtClaimsSet.builder()
                .issuer("geoportal-nsdi-kg")
                .subject(u.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.jwtTtlMinutes(), ChronoUnit.MINUTES))
                .claim("name", u.name())
                .claim("role", u.role());
        if (u.orgId() != null) {
            c.claim("orgId", u.orgId().toString()).claim("orgCode", u.orgCode()).claim("orgName", u.orgName());
        }
        JwsHeader h = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(h, c.build())).getTokenValue();
    }
}
