package com.shivang.obd.architecture;

import com.shivang.obd.ObdApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ArchitectureTest {

    @Test
    void modularBoundariesAreIntact() {
        ApplicationModules modules = ApplicationModules.of(ObdApplication.class);
        modules.verify();
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
