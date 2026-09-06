package com.github.fmaiassistent.repository;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitionRepositoryCacheTest {
    @Test
    void zeroArgumentQueriesWithDifferentMeaningsUseDifferentCaches() throws NoSuchMethodException {
        Method names = CompetitionRepository.class.getMethod("findDistinctNameByOrderByNameAsc");
        Method genders = CompetitionRepository.class.getMethod("findDistinctGenders");

        assertThat(names.getAnnotation(Cacheable.class).cacheNames())
                .doesNotContainAnyElementsOf(java.util.List.of(
                        genders.getAnnotation(Cacheable.class).cacheNames()));
    }
}
