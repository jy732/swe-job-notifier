package com.github.jingyangyu.swejobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for Oracle Cloud (Taleo) company career sites. */
@ConfigurationProperties(prefix = "job.oraclecloud")
@Getter
@Setter
public class OracleCloudProperties {

    private List<OracleCloudCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<OracleCloudCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Oracle Cloud career site. */
    @Getter
    @Setter
    public static class OracleCloudCompany {
        private String name;

        /**
         * The Oracle Cloud subdomain, e.g. "hcm-yourcompany" in
         * hcm-yourcompany.fa.us2.oraclecloud.com.
         */
        private String subdomain;

        /** The Oracle Cloud region, e.g. "us2", "us6", "em2". */
        private String region;

        /** The career site number/identifier, e.g. "CX_1001", "CX". */
        private String siteNumber;

        /**
         * Returns the base URL for this Oracle Cloud instance. Handles instances with no region
         * segment (e.g. {@code jpmc.fa.oraclecloud.com} vs {@code edel.fa.us2.oraclecloud.com}).
         */
        public String baseUrl() {
            if (region == null || region.isBlank()) {
                return String.format("https://%s.fa.oraclecloud.com", subdomain);
            }
            return String.format("https://%s.fa.%s.oraclecloud.com", subdomain, region);
        }

        /** Returns the REST API URL for job requisition search with pagination. */
        public String apiUrl(int limit, int offset) {
            return String.format(
                    "%s/hcmRestApi/resources/latest/recruitingCEJobRequisitions"
                            + "?onlyData=true"
                            + "&expand=requisitionList.secondaryLocations,flexFieldsFacet.values"
                            + "&finder=findReqs;siteNumber=%s,facetsList=LOCATIONS%%3B"
                            + "WORK_LOCATIONS%%3BWORKPLACE_TYPES%%3BTITLES%%3BCATEGORIES%%3B"
                            + "ORGANIZATIONS%%3BPOSTING_DATES%%3BFLEX_FIELDS"
                            + "&limit=%d&offset=%d",
                    baseUrl(), siteNumber, limit, offset);
        }

        /** Returns the public career site URL for a specific job requisition. */
        public String jobUrl(Object requisitionId) {
            return String.format(
                    "%s/hcmUI/CandidateExperience/en/sites/%s/job/%s",
                    baseUrl(), siteNumber, requisitionId);
        }
    }
}
