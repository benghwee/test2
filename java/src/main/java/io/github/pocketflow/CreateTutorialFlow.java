package io.github.pocketflow;

import io.github.pocketflow.nodes.AnalyzeRelationships;
import io.github.pocketflow.nodes.CombineTutorial;
import io.github.pocketflow.nodes.FetchRepo;
import io.github.pocketflow.nodes.IdentifyAbstractions;
import io.github.pocketflow.nodes.OrderChapters;
import io.github.pocketflow.nodes.WriteChapters;

/** Mirrors {@code flow.py}: builds and wires the tutorial generation flow. */
public final class CreateTutorialFlow {

    private CreateTutorialFlow() {
    }

    public static Flow createTutorialFlow() {
        FetchRepo fetchRepo = new FetchRepo();
        IdentifyAbstractions identifyAbstractions = new IdentifyAbstractions();
        AnalyzeRelationships analyzeRelationships = new AnalyzeRelationships();
        OrderChapters orderChapters = new OrderChapters();
        WriteChapters writeChapters = new WriteChapters();
        CombineTutorial combineTutorial = new CombineTutorial();

        fetchRepo.connect(identifyAbstractions);
        identifyAbstractions.connect(analyzeRelationships);
        analyzeRelationships.connect(orderChapters);
        orderChapters.connect(writeChapters);
        writeChapters.connect(combineTutorial);

        return new Flow(fetchRepo);
    }
}
