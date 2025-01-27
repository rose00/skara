/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class CheckWorkItem extends PullRequestWorkItem {
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final Map<String, String> blockingLabels;

    private final Pattern metadataComments = Pattern.compile("<!-- (add|remove) contributor");
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    CheckWorkItem(PullRequest pr, HostedRepository censusRepo, String censusRef, Map<String, String> blockingLabels, Consumer<RuntimeException> errorHandler) {
        super(pr, errorHandler);
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.blockingLabels = blockingLabels;
    }

    private String encodeReviewer(HostUserDetails reviewer, CensusInstance censusInstance) {
        var census = censusInstance.census();
        var project = censusInstance.project();
        var namespace = censusInstance.namespace();
        var contributor = namespace.get(reviewer.id());
        if (contributor == null) {
            return "unknown-" + reviewer.id();
        } else {
            var censusVersion = census.version().format();
            var userName = contributor.username();
            return contributor.username() + project.isLead(userName, censusVersion) +
                    project.isReviewer(userName, censusVersion) + project.isCommitter(userName, censusVersion) +
                    project.isAuthor(userName, censusVersion);
        }
    }

    String getMetadata(String title, String body, List<Comment> comments, List<Review> reviews, Set<String> labels,
                       CensusInstance censusInstance, Hash target) {
        try {
            var approverString = reviews.stream()
                                        .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                        .map(review -> encodeReviewer(review.reviewer(), censusInstance) + review.hash().hex())
                                        .sorted()
                                        .collect(Collectors.joining());
            var commentString = comments.stream()
                                        .filter(comment -> comment.author().id().equals(pr.repository().host().getCurrentUserDetails().id()))
                                        .flatMap(comment -> comment.body().lines())
                                        .filter(line -> metadataComments.matcher(line).find())
                                        .collect(Collectors.joining());
            var labelString = labels.stream()
                                    .sorted()
                                    .collect(Collectors.joining());
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(title.getBytes(StandardCharsets.UTF_8));
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            digest.update(approverString.getBytes(StandardCharsets.UTF_8));
            digest.update(commentString.getBytes(StandardCharsets.UTF_8));
            digest.update(labelString.getBytes(StandardCharsets.UTF_8));
            digest.update(target.hex().getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    private boolean currentCheckValid(CensusInstance censusInstance, List<Comment> comments, List<Review> reviews, Set<String> labels) {
        var hash = pr.getHeadHash();
        var targetHash = pr.getTargetHash();
        var metadata = getMetadata(pr.getTitle(), pr.getBody(),comments, reviews, labels, censusInstance, targetHash);
        var currentChecks = pr.getChecks(hash);

        if (currentChecks.containsKey("jcheck")) {
            var check = currentChecks.get("jcheck");
            // Check if the currently running check seems stale - perhaps the checker failed to complete
            if (check.completedAt().isEmpty()) {
                var runningTime = Duration.between(check.startedAt().toInstant(), Instant.now());
                if (runningTime.toMinutes() > 10) {
                    log.warning("Previous jcheck running for more than 10 minutes - checking again");
                } else {
                    log.finer("Jcheck in progress for " + runningTime.toMinutes() + " minutes, not starting another one");
                    return true;
                }
            } else {
                if (check.metadata().isPresent() && check.metadata().get().equals(metadata)) {
                    log.finer("No activity since last check, not checking again");
                    return true;
                } else {
                    log.info("PR updated after last check, checking again");
                    if (check.metadata().isPresent() && (!check.metadata().get().equals(metadata))) {
                        log.fine("Previous metadata: " + check.metadata().get() + " - current: " + metadata);
                    }
                }
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return "CheckWorkItem@" + pr.repository().getName() + "#" + pr.getId();
    }

    @Override
    public void run(Path scratchPath) {
        // First determine if the current state of the PR has already been checked
        var census = CensusInstance.create(censusRepo, censusRef, scratchPath.resolve("census"), pr);
        var comments = pr.getComments();
        var allReviews = pr.getReviews();
        var labels = new HashSet<>(pr.getLabels());

        // Filter out the active reviews
        var activeReviews = PullRequestInstance.filterActiveReviews(allReviews);
        if (!currentCheckValid(census, comments, activeReviews, labels)) {
            if (labels.contains("integrated")) {
                log.info("Skipping check of integrated PR");
                return;
            }

            try {
                var prInstance = new PullRequestInstance(scratchPath.resolve("pr"), pr);
                CheckRun.execute(this, pr, prInstance, comments, allReviews, activeReviews, labels, census, blockingLabels);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
