package xyz.avasilev.taskcomm.ui;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.stereotype.Component;

/**
 * Границы модуля taskcomm-ui (docs/adr/0149-web-interface-module.md, docs/adr/0087-architecture-rules.md):
 * интерфейс стоит на REST и свойствах стартера и не трогает внутреннюю реализацию подсистем; сканирования
 * нет; автоконфигурация находится Spring Boot через файл imports.
 */
@AnalyzeClasses(packages = "xyz.avasilev.taskcomm.ui")
class UiArchitectureTest {

    @ArchTest
    static final ArchRule UI_DOES_NOT_USE_INTERNALS_OF_STARTER = noClasses()
            .that()
            .resideInAPackage("xyz.avasilev.taskcomm.ui..")
            .should()
            .dependOnClassesThat(resideInAPackage("xyz.avasilev.taskcomm..internal..")
                    .and(DescribedPredicate.not(resideInAPackage("xyz.avasilev.taskcomm.ui.internal.."))));

    @ArchTest
    static final ArchRule NO_SCANNED_STEREOTYPES = noClasses()
            .that()
            .resideInAPackage("xyz.avasilev.taskcomm.ui..")
            .should()
            .beAnnotatedWith(Component.class);

    @Test
    void autoConfigurationIsListedInImportsFile() {
        final List<String> candidates = new ArrayList<>();
        ImportCandidates.load(AutoConfiguration.class, UiArchitectureTest.class.getClassLoader())
                .forEach(candidates::add);

        assertThat(candidates).contains(TaskcommUiAutoConfiguration.class.getName());
    }
}
