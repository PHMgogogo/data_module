package com.project.phm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.phm.adapter.dto.DataSource;
import com.project.phm.adapter.dto.UnifiedTimeSeriesRequest;
import com.project.phm.entity.ConfigItem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformRouteServiceTest {

    @Test
    void buildsRouteTreeIncrementally() {
        PlatformRouteService routeService = new PlatformRouteService();

        routeService.replaceModels(DataSource.HANGXIN, Arrays.asList(
                new PlatformRouteService.ModelDefinition("A320:11", "A320"),
                new PlatformRouteService.ModelDefinition("A321:12", "A321")));
        assertEquals(DataSource.HANGXIN, routeService.resolveModel("A320:11"));
        assertEquals(DataSource.HANGXIN, routeService.resolveModel("A320"));
        assertEquals("A320", routeService.realAirplaneType("A320"));

        routeService.replaceAircraft(DataSource.HANGXIN, "A320:11",
                Arrays.asList("H-001", "H-002"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("H-001", "H-002")),
                routeService.aircraftNumbersForModel("A320"));
        assertEquals(DataSource.HANGXIN, routeService.resolveAircraftRef("H-001").getSource());

        routeService.replaceSorties(DataSource.HANGXIN, "H-001",
                Collections.singletonList(PlatformRouteService.SortieRef.external(
                        DataSource.HANGXIN, "HX-SORTIE-1", "A320", "H-001", "F-001",
                        "2026-09-18 10:00:00", "2026-09-18 11:00:00", "PG-HX-1")));

        PlatformRouteService.SortieRef sortie = routeService.resolveSortie("HX-SORTIE-1");
        assertNotNull(sortie);
        assertEquals(DataSource.HANGXIN, sortie.getSource());
        assertEquals("A320:11", sortie.getModelKey());
        assertEquals(sortie, routeService.resolveParameterGroup("PG-HX-1"));
    }

    @Test
    void refreshingModelsPreservesLoadedChildrenForTheSameModel() {
        PlatformRouteService routeService = new PlatformRouteService();
        routeService.replaceModels(DataSource.SAN_SAN, Collections.singletonList(
                new PlatformRouteService.ModelDefinition("B737:22", "B737")));
        routeService.replaceAircraft(DataSource.SAN_SAN, "B737:22",
                Collections.singletonList("S-001"));

        routeService.replaceModels(DataSource.SAN_SAN, Collections.singletonList(
                new PlatformRouteService.ModelDefinition("B737:22", "B737")));

        assertEquals(Collections.singleton("S-001"),
                routeService.aircraftNumbersForModel("B737:22"));
    }

    @Test
    void bindTableEnforcesOneToOneOnLocalSortie() {
        PlatformRouteService routeService = new PlatformRouteService();
        routeService.replaceModels(DataSource.LOCAL, Collections.singletonList(
                new PlatformRouteService.ModelDefinition("LOCAL-A", "LOCAL-A")));
        routeService.replaceAircraft(DataSource.LOCAL, "LOCAL-A",
                Arrays.asList("L-001", "L-002"));
        routeService.replaceSorties(DataSource.LOCAL, "L-001",
                Collections.singletonList(PlatformRouteService.SortieRef.local(
                        1L, "LOCAL-A", "L-001")));
        routeService.replaceSorties(DataSource.LOCAL, "L-002",
                Collections.singletonList(PlatformRouteService.SortieRef.local(
                        2L, "LOCAL-A", "L-002")));

        routeService.bindTable("1", "engine");
        assertEquals(Collections.singleton("engine"), routeService.tablesForSortie("1"));

        assertThrows(IllegalArgumentException.class,
                () -> routeService.bindTable("1", "apu"));
        assertThrows(IllegalArgumentException.class,
                () -> routeService.bindTable("2", "engine"));
    }

    @Test
    void skipsAircraftWhoseModelIsNotRegistered() {
        PlatformRouteService routeService = new PlatformRouteService();
        routeService.replaceModels(DataSource.SAN_SAN, Collections.singletonList(
                new PlatformRouteService.ModelDefinition("B737:22", "B737")));
        routeService.replaceAircraft(DataSource.SAN_SAN, "UNKNOWN:1",
                Collections.singletonList("UNKNOWN-1"));

        assertNull(routeService.resolveAircraftRef("UNKNOWN-1"));
    }

    @Test
    void filtersExternalConfigItemsByModelAircraftNumbers() {
        ConfigItem matched = configItem("0003");
        ConfigItem other = configItem("0004");
        ConfigItem blank = configItem(null);

        assertEquals(Collections.singletonList(matched),
                AircraftConfigService.filterConfigItemsByAircraftNumbers(
                        Arrays.asList(matched, other, blank),
                        new LinkedHashSet<>(Collections.singletonList("0003"))));
    }

    @Test
    void timeSeriesRequestAcceptsSortieKeyAliasWithoutAddingAProperty() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        UnifiedTimeSeriesRequest aliasRequest = objectMapper.readValue(
                "{\"sortieKey\":\"sortie-key-1\"}", UnifiedTimeSeriesRequest.class);
        assertEquals("sortie-key-1", aliasRequest.resolveSortieKey());

        UnifiedTimeSeriesRequest legacyRequest = objectMapper.readValue(
                "{\"sortieId\":\"sortie-id-2\"}", UnifiedTimeSeriesRequest.class);
        assertEquals("sortie-id-2", legacyRequest.resolveSortieKey());
    }

    private static ConfigItem configItem(String ssfjh) {
        ConfigItem item = new ConfigItem();
        item.setModelCode(ssfjh);
        item.setEquipmentName("item-" + ssfjh);
        return item;
    }
}
