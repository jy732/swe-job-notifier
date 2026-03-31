package com.github.jingyangyu.swejobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Configuration properties for Workday company career sites. */
@ConfigurationProperties(prefix = "job.workday")
@Getter
@Setter
public class WorkdayProperties {

    private List<WorkdayCompany> companies = new ArrayList<>();

    /**
     * Finds a Workday company configuration by name.
     *
     * @param name the company name to look up
     * @return the matching company config, or empty if not found
     */
    public Optional<WorkdayCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Workday company career site. */
    @Getter
    @Setter
    public static class WorkdayCompany {
        private String name;
        private String subdomain;
        private int instance;
        private String site;

        /** Returns the base URL for this Workday instance. */
        public String baseUrl() {
            return String.format(
                    "https://%s.wd%d.myworkdayjobs.com", subdomain, instance);
        }

        /** Returns the CXS API URL for job search requests. */
        public String apiUrl() {
            return String.format("%s/wday/cxs/%s/%s/jobs", baseUrl(), subdomain, site);
        }

        /** Returns the public job URL for the given external path. */
        public String jobUrl(String externalPath) {
            return String.format("%s/en-US/%s%s", baseUrl(), site, externalPath);
        }
    }
}
