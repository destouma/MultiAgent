package com.multiagent.desktop;

import com.multiagent.desktop.persistence.ConversationStore;
import com.multiagent.desktop.service.CheckpointService;
import com.multiagent.desktop.service.ChatService;
import com.multiagent.desktop.service.ConfigService;
import com.multiagent.desktop.service.OrchestratorService;
import com.multiagent.desktop.service.PersonaRegistry;
import com.multiagent.desktop.ui.MainWindow;
import com.multiagent.desktop.ui.viewmodel.ChatViewModel;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX entry point - the Java equivalent of desktop/electron/main.ts's window bootstrap. */
public class App extends Application {
    private ConversationStore store;
    private ChatViewModel primaryViewModel;
    private ChatViewModel secondaryViewModel;

    @Override
    public void start(Stage stage) {
        ConfigService configService = new ConfigService();
        store = new ConversationStore();
        PersonaRegistry personaRegistry = new PersonaRegistry();
        ChatService chatService = new ChatService(store);
        OrchestratorService orchestratorService = new OrchestratorService(store, personaRegistry);
        CheckpointService checkpointService = new CheckpointService(store);

        // Two ChatViewModel instances sharing the same backend services - one per split-view
        // pane, mirroring chatStore.ts's useChatStore (primary) / useSecondaryChatStore split.
        // The secondary pane is created eagerly but stays hidden until "Side by side" is used.
        primaryViewModel = new ChatViewModel(store, personaRegistry, configService, chatService,
                orchestratorService, checkpointService);
        secondaryViewModel = new ChatViewModel(store, personaRegistry, configService, chatService,
                orchestratorService, checkpointService);
        primaryViewModel.addSibling(secondaryViewModel);
        secondaryViewModel.addSibling(primaryViewModel);

        primaryViewModel.bootstrap();
        secondaryViewModel.bootstrap();

        MainWindow window = new MainWindow(primaryViewModel, secondaryViewModel);
        Scene scene = new Scene(window.getView(), 1300, 760);
        stage.setScene(scene);
        // Theme needs the Scene to already exist (applyTheme swaps its stylesheet list),
        // so this can't happen until after the Scene is attached above.
        window.applyTheme(configService.getSettings().getTheme());

        stage.setTitle("MultiAgent (Java)");
        stage.show();
    }

    @Override
    public void stop() {
        primaryViewModel.shutdown();
        store.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
