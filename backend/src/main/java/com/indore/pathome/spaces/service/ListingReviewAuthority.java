package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Who may reach the landlord listing-review operations, and under what authority.
 *
 * <p>Exactly two principals are admitted:
 *
 * <ul>
 *   <li>a caller presenting both the legacy {@code ROLE_ADMIN} credential and the same live current
 *       persisted role/profile/global-grant facts required by the Phase 1 cutover; and</li>
 *   <li>a caller holding a <em>live</em>, effective {@code STAFF_ADMIN@GLOBAL} grant on an active staff
 *       profile.</li>
 * </ul>
 *
 * <p>Nothing else is admitted. An ordinary tenant, a sub-administrator, an inactive or unknown staff
 * profile, a revoked, not-yet-effective or expired grant, and every narrower capability
 * ({@code CITY_TEAM_ADMIN}, {@code OPS_INTAKE}, {@code OPS_COORDINATE}, {@code OPS_SUPERVISE}) are all
 * refused: no narrower capability substitutes for the global one, so the extension cannot be reached by
 * escalating a City or Team scope.
 *
 * <p>Neither decision is taken from a token role claim as current authority. It is resolved from live database facts on
 * every call, and {@link #revalidate(Reviewer)} re-decides it again so a grant revoked between the
 * entry decision and the guarded mutation is honoured under the same transaction.
 *
 * <p>The decision is carried by {@link Reviewer}, which only this component can mint: its constructor is
 * private and every factory is private, so no other class - in this package or any other - can build one.
 * A caller therefore cannot assert an authority of its own; it can only present the credential the
 * container established and let this component decide.
 *
 * <p>Every decision is bound to the credential the container established for the request it was taken
 * in. The entry decision is taken only for the credential that is authenticated on this thread right
 * now - a credential supplied by the caller that is not that one is refused, however it was built - so
 * the decision cannot be taken for an authority nobody is currently presenting. The verdict then
 * remembers that exact credential, and the guarded {@link #revalidate(Reviewer)} /
 * {@link #revalidateLegacyAdministrator(Reviewer)} re-decisions accept it only while that same
 * credential is still the authenticated one. A verdict is therefore single-use within the request that
 * minted it: it cannot be replayed in another request, for another principal, or even for the same staff
 * User's next credential, and the ambient authority it re-decides is re-derived from the live credential
 * and live database facts rather than remembered.
 *
 * <p>{@link #requireLegacyAdministrator(Authentication)} and
 * {@link #revalidateLegacyAdministrator(Reviewer)} are the narrower, {@code ROLE_ADMIN}-only decisions
 * for the listing-review operations that stay legacy: requesting listing changes, and approving or
 * refusing a landlord revision. The staff extension is not admitted by them at any point.
 */
@Component
public class ListingReviewAuthority {

    /** The authority the previous {@code hasRole('ADMIN')} rule consulted. */
    private static final String LEGACY_ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final StaffAccessService access;
    private final BackOfficePropertyAuthority propertyAuthority;

    public ListingReviewAuthority(StaffAccessService access, BackOfficePropertyAuthority propertyAuthority) {
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.propertyAuthority = Objects.requireNonNull(propertyAuthority, "propertyAuthority must not be null");
    }

    /**
     * The entry decision for one listing-review request. Throws rather than returning a verdict, so a
     * caller cannot accidentally ignore it.
     *
     * <p>The decision is taken for the credential the container established for this request and for no
     * other: a foreign credential - one the caller built, borrowed, or kept from an earlier request - is
     * refused even when its authorities look exactly like the ones required.
     */
    public Reviewer require(Authentication authentication) {
        Authentication request = currentAuthenticatedRequest();
        if (request == null || authentication != request) {
            throw new AccessDeniedException("Authenticated staff or administrator authority is required");
        }
        if (hasLegacyAdminAuthority(request)) {
            Long userId = authenticatedUserId(request);
            if (userId == null) {
                throw new AccessDeniedException("Administrator User identity is required");
            }
            BackOfficePropertyAuthority.Actor actor = propertyAuthority.require(
                    BackOfficePropertyAuthority.Action.LEGACY_REVIEW, request);
            return Reviewer.legacyAdministrator(request, actor.userId());
        }
        Long userId = authenticatedUserId(request);
        if (userId == null) {
            throw new AccessDeniedException("Current global Staff Admin authority is required");
        }
        // Live database facts: an active staff profile plus an unrevoked, already-effective, unexpired
        // STAFF_ADMIN@GLOBAL grant. Re-read here rather than trusted from the token.
        access.requireGlobalAdmin(userId);
        return Reviewer.globalStaffAdministrator(request, userId);
    }

    /**
     * The entry decision for the listing-review operations that stay {@code ROLE_ADMIN}-only: requesting
     * listing changes, and approving or rejecting a landlord revision. Only the legacy administrator
     * authority is admitted, so the service-level rule matches the {@code hasRole('ADMIN')} rule those
     * requests already carry and no staff capability can reach them - neither here nor at the guarded
     * re-decision. The credential must be the one authenticated for this request, exactly as
     * {@link #require(Authentication)} demands.
     */
    public Reviewer requireLegacyAdministrator(Authentication authentication) {
        Authentication request = currentAuthenticatedRequest();
        if (request == null || authentication != request || !hasLegacyAdminAuthority(request)
                || authenticatedUserId(request) == null) {
            throw new AccessDeniedException("Administrator authority is required");
        }
        BackOfficePropertyAuthority.Actor actor = propertyAuthority.require(
                BackOfficePropertyAuthority.Action.LEGACY_REVIEW, request);
        return Reviewer.legacyAdministrator(request, actor.userId());
    }

    /**
     * The guarded re-decision. Every listing-review operation calls it on the same thread and transaction
     * it will act in, and every mutation calls it <em>after</em> acquiring the listing row lock, so a
     * grant revoked between the entry decision and the lock is refused before any mutation is flushed.
     *
     * <p>Nothing here is taken on trust from the {@link Reviewer} instance. The verdict must name the very
     * credential that is authenticated on this thread right now, and the decision is then re-derived from
     * that live credential: the legacy administrator path from its own granted authority, and the staff
     * path from its stable identity plus live database facts. A verdict minted for another principal,
     * another request, or merely another credential of the same staff User, is refused.
     */
    public void revalidate(Reviewer reviewer) {
        Authentication request = currentAuthenticatedRequest();
        if (reviewer == null || reviewer.credential != request) {
            throw new AccessDeniedException("Current global Staff Admin authority is required");
        }
        if (reviewer.legacyAdmin()) {
            if (!hasLegacyAdminAuthority(request)) {
                throw new AccessDeniedException("Administrator authority is required");
            }
            BackOfficePropertyAuthority.Actor actor = propertyAuthority.require(
                    BackOfficePropertyAuthority.Action.LEGACY_REVIEW, request);
            if (!reviewer.userId().equals(actor.userId())) {
                throw new AccessDeniedException("Administrator authority is required");
            }
            return;
        }
        // The staff decision belongs to the stable User identity the verdict was minted for. The credential
        // is the same object the decision was taken from, so the identity cannot have changed underneath it.
        access.requireGlobalAdmin(reviewer.userId());
    }

    /**
     * The guarded re-decision for the operations that stay {@code ROLE_ADMIN}-only.
     *
     * <p>Same credential binding as {@link #revalidate(Reviewer)} plus the narrow rule: the verdict must
     * be the legacy administrator's, and the credential authenticated for this call must itself still
     * carry {@code ROLE_ADMIN}. A staff verdict is refused here even when the call it belongs to is
     * current, genuine and backed by a live {@code STAFF_ADMIN@GLOBAL} grant - these operations are not
     * part of the extension, and no staff authority reaches them by any route.
     */
    public void revalidateLegacyAdministrator(Reviewer reviewer) {
        Authentication request = currentAuthenticatedRequest();
        if (reviewer == null || reviewer.credential != request || !reviewer.legacyAdmin()
                || !hasLegacyAdminAuthority(request)) {
            throw new AccessDeniedException("Administrator authority is required");
        }
        BackOfficePropertyAuthority.Actor actor = propertyAuthority.require(
                BackOfficePropertyAuthority.Action.LEGACY_REVIEW, request);
        if (!reviewer.userId().equals(actor.userId())) {
            throw new AccessDeniedException("Administrator authority is required");
        }
    }

    /**
     * Holds the database evidence this verdict's authority rests on for the rest of the transaction, and
     * must be called <em>before</em> the caller takes any lock that comes after that evidence in the
     * established hierarchy - in the review operations, before the Listing row lock.
     *
     * <p>The hold is what makes a revocation ordered rather than interleaved. The guarded re-decision
     * after the Listing lock re-reads the same facts, so with the evidence held from before that lock, a
     * revocation that commits first is observed (and refused) while a revocation that arrives second
     * waits for this transaction to commit - there is no window in which an observer can read a
     * revocation and still see the publication land.
     *
     * <p>A legacy administrator's decision is now backed by the same current role/profile/global-grant
     * evidence as the uploader. Holding those rows before the listing lock is what makes a concurrent
     * demotion, deactivation or grant revocation ordered against the mutation.
     */
    public void holdAuthorityEvidence(Reviewer reviewer) {
        if (reviewer == null) {
            throw new AccessDeniedException("Current global Staff Admin authority is required");
        }
        if (reviewer.legacyAdmin) {
            Authentication request = currentAuthenticatedRequest();
            if (request == null || reviewer.credential != request) {
                throw new AccessDeniedException("Administrator authority is required");
            }
            propertyAuthority.holdEvidenceForCurrentMutation(
                    BackOfficePropertyAuthority.Action.LEGACY_REVIEW);
            return;
        }
        access.holdGlobalAdminEvidence(reviewer.userId);
    }

    /**
     * The real acting {@code User} this verdict's action is attributed to, for durable audit.
     *
     * <p>The identity is taken from the very credential the verdict was decided for - the stable,
     * server-verified User id the container authenticated for this request - and resolved against
     * {@code users} in this transaction. It is never derived from a display name, a claim the caller
     * supplied, or a fabricated default, and an action whose actor cannot be resolved is refused rather
     * than recorded without an actor. Both principal classes are attributed the same way; for the legacy
     * administrator this is the identity their own signed credential carries.
     */
    public User attributableActor(Reviewer reviewer) {
        Authentication request = currentAuthenticatedRequest();
        if (reviewer == null || reviewer.credential != request) {
            throw new AccessDeniedException("An attributable staff or administrator identity is required");
        }
        return access.requireAttributableUser(authenticatedUserId(request));
    }

    /**
     * The credential the container authenticated for the request running on this thread, or {@code null}
     * when no authenticated, non-anonymous credential is established for it.
     */
    private static Authentication currentAuthenticatedRequest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return isAuthenticated(authentication) ? authentication : null;
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean hasLegacyAdminAuthority(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (LEGACY_ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stable {@code User} identity carried by the verified credential, or {@code null} when the
     * credential does not carry one (for example a principal established by a non-JWT login).
     */
    private static Long authenticatedUserId(Authentication authentication) {
        if (authentication.getDetails() instanceof PathomeAuthenticationDetails details
                && details.getUserId() != null && details.getUserId() > 0) {
            return details.getUserId();
        }
        return null;
    }

    /**
     * The one shape a resolved listing-review authority can have, minted only by this component.
     *
     * <p>The constructor and both factories are private to {@link ListingReviewAuthority}, and the class is
     * final. No caller can construct a verdict, subclass one, or assert one by passing a boolean, so the
     * only way to hold a {@code Reviewer} is to have presented an authenticated principal to
     * {@link #require(Authentication)} or {@link #requireLegacyAdministrator(Authentication)}.
     *
     * <p>{@code userId} is always present. Both legacy and grant-backed review paths are now bound to
     * the stable authenticated User whose current facts are re-decided.
     */
    public static final class Reviewer {
        private final Authentication credential;
        private final Long userId;
        private final boolean legacyAdmin;

        private Reviewer(Authentication credential, Long userId, boolean legacyAdmin) {
            this.credential = Objects.requireNonNull(credential,
                    "A verdict must name the credential it was decided for");
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("A reviewer requires a stable User identity");
            }
            this.userId = userId;
            this.legacyAdmin = legacyAdmin;
        }

        private static Reviewer legacyAdministrator(Authentication credential, Long userId) {
            return new Reviewer(credential, userId, true);
        }

        private static Reviewer globalStaffAdministrator(Authentication credential, Long userId) {
            return new Reviewer(credential, userId, false);
        }

        /** The stable {@code User} identity this verdict belongs to; {@code null} for the legacy administrator. */
        public Long userId() {
            return userId;
        }

        /** Whether this verdict is the legacy {@code ROLE_ADMIN} authority rather than a staff grant. */
        public boolean legacyAdmin() {
            return legacyAdmin;
        }
    }
}
