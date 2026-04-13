package com.github.jingyangyu.swejobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for iCIMS company career portals. */
@ConfigurationProperties(prefix = "job.icims")
@Getter
@Setter
public class IcimsProperties {

    private List<IcimsCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<IcimsCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single iCIMS company career portal. */
    @Getter
    @Setter
    public static class IcimsCompany {
        private String name;

        /** The portal subdomain, e.g. "careers-booz" for careers-booz.icims.com. */
        private String subdomain;

        /**
         * Optional custom domain override (e.g. "careers.company.com"). If set, used instead of
         * subdomain.
         */
        private String customDomain;

        /** Returns the base URL for this iCIMS career portal. */
        public String baseUrl() {
            if (customDomain != null && !customDomain.isBlank()) {
                return "https://" + customDomain;
            }
            return String.format("https://%s.icims.com", subdomain);
        }

        /** Returns the search API URL with pagination. */
        public String searchUrl(int limit, int offset) {
            return String.format(
                    "%s/jobs/search?pr=%d&o=%d&mode=job&iis=Job+Listing&schemaId=&in_iframe=1",
                    baseUrl(), limit, offset);
        }

        /** Returns the public URL for a specific job. */
        public String jobUrl(String jobId) {
            return String.format("%s/jobs/%s/job", baseUrl(), jobId);
        }
    }
}
