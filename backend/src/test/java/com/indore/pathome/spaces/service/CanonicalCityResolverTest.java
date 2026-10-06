package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalCityResolverTest {
    private final SupportedCityRepository repository = mock(SupportedCityRepository.class);
    private final CanonicalCityResolver resolver = new CanonicalCityResolver(repository);

    @Test
    void resolvesCanonicalCodeAfterSafeCaseAndWhitespaceNormalization() {
        SupportedCity indore = new SupportedCity("indore", "Indore", true);
        when(repository.findByCode("indore")).thenReturn(Optional.of(indore));

        assertSame(indore, resolver.resolveCode("  INDORE  ").orElseThrow());
        verify(repository).findByCode("indore");
    }

    @Test
    void codeLookupRejectsNonCodeTextInsteadOfGuessing() {
        assertTrue(resolver.resolveCode("Indore City").isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void unknownCanonicalCodeRemainsUnresolved() {
        when(repository.findByCode("indoree")).thenReturn(Optional.empty());

        assertTrue(resolver.resolveCode("Indoree").isEmpty());
        verify(repository).findByCode("indoree");
    }

    @Test
    void resolvesOneExactCaseInsensitiveDisplayNameMatch() {
        SupportedCity bhopal = new SupportedCity("bhopal", "Bhopal", true);
        when(repository.findExactDisplayNameMatches("bhopal")).thenReturn(List.of(bhopal));

        assertSame(bhopal, resolver.resolveDisplayName("  bHoPaL ").orElseThrow());
        verify(repository).findExactDisplayNameMatches("bhopal");
    }

    @Test
    void duplicateDisplayNamesAndUnknownNamesFailClosed() {
        SupportedCity indore = new SupportedCity("indore", "Indore", true);
        SupportedCity alternate = new SupportedCity("indore-central", "Indore", true);
        when(repository.findExactDisplayNameMatches("indore")).thenReturn(List.of(indore, alternate));
        when(repository.findExactDisplayNameMatches("indoree")).thenReturn(List.of());

        assertTrue(resolver.resolveDisplayName("Indore").isEmpty());
        assertTrue(resolver.resolveDisplayName("Indoree").isEmpty());
        verify(repository).findExactDisplayNameMatches("indore");
        verify(repository).findExactDisplayNameMatches("indoree");
    }
}
