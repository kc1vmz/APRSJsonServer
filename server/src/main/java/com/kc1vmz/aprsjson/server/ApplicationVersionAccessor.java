/*
 *
 * APRSWelcomeCenter
 * Copyright (c) 2026 John Rokicki KC1VMZ
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * https://www.gnu.org/licenses/.
 *
 * https://www.kc1vmz.com
 */
package com.kc1vmz.aprsjson.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ApplicationVersionAccessor {
    @Value("${application-version.application-name}")
    private String applicationName;

    @Value("${application-version.version}")
    private String version;

    @Value("${application-version.author}")
    private String author;

    @Value("${application-version.copyright-year}")
    private String copyrightYear;

    @Value("${application-version.website}")
    private String website;

    @Value("${application-version.license-name}")
    private String licenseName;

    @Value("${application-version.license-url}")
    private String licenseUrl;

    public Mono<ApplicationVersion> find() {
        return Mono.just(new ApplicationVersion(applicationName, version, author, copyrightYear, website,
                licenseName, licenseUrl));
    }

    public record ApplicationVersion(String applicationName, String version, String author, String copyrightYear,
                                     String website, String licenseName, String licenseUrl) {}
}
