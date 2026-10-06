package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.SupportedCityRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Loads active canonical city display names into the legacy public search compatibility view at startup. */
@Component
public class SupportedCityRegistryInitializer implements ApplicationRunner {
    private final SupportedCityRepository supportedCityRepository;

    public SupportedCityRegistryInitializer(SupportedCityRepository supportedCityRepository) {
        this.supportedCityRepository = supportedCityRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        CityRegistry.replaceSupportedCities(supportedCityRepository.findAllByActiveTrueOrderByDisplayNameAsc()
                .stream()
                .map(city -> city.getDisplayName())
                .toList());
    }
}
