package org.prelle.genesis;

import java.awt.SplashScreen;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.prelle.genesis.jobs.CheckForUpdates;
import org.prelle.genesis.screens.InstallPluginsNode;
import org.prelle.genesis.screens.MainScreen;
import org.prelle.javafx.AlertType;
import org.prelle.javafx.ModernUI;
import org.prelle.javafx.ScreenManager;
import org.prelle.rpgframework.jfx.RPGFrameworkJFXConstants;

import de.rpgframework.ConfigContainer;
import de.rpgframework.ConfigOption;
import de.rpgframework.ConfigOption.Type;
import de.rpgframework.PluginDescriptor;
import de.rpgframework.PluginRegistry;
import de.rpgframework.RPGFramework;
import de.rpgframework.RPGFrameworkConstants;
import de.rpgframework.RPGFrameworkInitCallback;
import de.rpgframework.RPGFrameworkLoader;
import de.rpgframework.ResourceI18N;
import de.rpgframework.boot.StandardBootSteps;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.application.Preloader.PreloaderNotification;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

public class Genesis5Main extends Application {

	private static Logger logger ;

	private static ResourceBundle RES;

	public class MessageNotification implements PreloaderNotification {
		private String message;
		public MessageNotification(String mess) {
			this.message=mess;
		}
		public String toString() {
			return message;
		}
	}
	//	private ProgressBar bar;
	//	private Label status;
	//	private Label version;
	private RPGFrameworkInitCallback currentCallback;
	private ScreenManager manager;
	private Path installDir;
	
	private static HostServices host;

	//-------------------------------------------------------------------
	static {
		// Set language
		String locKey = Preferences.userRoot().node(RPGFramework.PREFERENCE_PATH).get(RPGFramework.PROP_LANGUAGE, null);
		if (locKey!=null) {
			Locale lang = Locale.forLanguageTag(locKey);
			if (lang!=null) {
				System.out.println("User chosen locale = "+lang);
				Locale.setDefault(lang);
			}
		}
		RES = ResourceBundle.getBundle(Genesis5Main.class.getName());
	}

	//-------------------------------------------------------------------
	@SuppressWarnings("unused")
	private static void printSystemProperties() {
		List<String> keys = new ArrayList<>();
		System.getProperties().keySet().forEach(key -> keys.add((String)key));
		Collections.sort(keys);
		keys.forEach(key -> System.out.println(key+" \t="+System.getProperty(key)));
	}

	//-------------------------------------------------------------------
	public static void main(String[] args) {
//		printSystemProperties();
		Application.launch(args);
	}

	private ConfigOption<Boolean> cfgSecondaryScreen;
	private ConfigContainer cfgGenesis;

	//-------------------------------------------------------------------
	public Genesis5Main() {
	}

	//-------------------------------------------------------------------
	private Path getInstallationDirectory() {
		CodeSource codeSource = getClass().getProtectionDomain().getCodeSource();
		URL codeSourceURL = codeSource.getLocation();
		System.err.println("codeSourceURL = "+codeSourceURL);
		if (codeSourceURL.getProtocol().equals("file")) {
			// Started from JAR
			try {
				Path ret = Paths.get(codeSourceURL.toURI()).getParent().getParent();
				return ret;
			} catch (URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (codeSourceURL.getProtocol().equals("jrt")) {
			// Started as runtime image
			Path ret = Paths.get(System.getProperty("java.home")).getParent().getParent();
			return ret;
		}
		
		System.err.println("Cannot detect installation directory");
		System.exit(ExitCodes.UNKNOWN_INSTALL_LOCATION);
		return null;
	}

	//-------------------------------------------------------------------
	private Path getUserInstallationDirectory() {
		Path home = Paths.get(System.getProperty("user.home"));
		if (System.getProperty("os.name").toLowerCase().indexOf("mac")>0) {
			// Running on Mac OS
			return home.resolve("Library").resolve("Application Support").resolve("de.rpgframework.Genesis").resolve(System.getProperty("profile","no-profile"));
		} else
			return home.resolve("genesis").resolve(System.getProperty("profile","no-profile"));
	}

	//-------------------------------------------------------------------
	private void initDetectVersion() throws IOException {
		String version = System.getProperty(Constants.KEY_APPLICATION_VERSION);
		if (version==null) {
			version = getClass().getPackage().getImplementationVersion();
		} 
		if (version==null) {
			String url = getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm();
			if (url.lastIndexOf("Genesis-")>0)
				version = url.substring(url.lastIndexOf("Genesis-")+8, url.lastIndexOf("."));
			else
				version = "Development";
		} 
		System.setProperty(Constants.KEY_APPLICATION_VERSION, version);
	}

	//-------------------------------------------------------------------
	private void initSetupLogging() throws IOException {
		Path logDir  = installDir.resolve("logs");
		System.setProperty("logdir", String.valueOf(logDir.toAbsolutePath()));
		System.out.println("use logdir "+logDir.toAbsolutePath());
		// Ensure log directory exists
		try {
			if (!Files.exists(logDir))
				Files.createDirectories(logDir);
			else if (!Files.isWritable(logDir)) {
				logDir  = Paths.get(System.getProperty("user.home"), "genesis-logs");
				System.setProperty("logdir", String.valueOf(logDir.toAbsolutePath()));
				if (!Files.exists(logDir))
					Files.createDirectories(logDir);
			}
		} catch (AccessDeniedException e) {
			System.out.println("Cannot create log directory "+logDir);
			logDir  = Paths.get(System.getProperty("user.home"), "genesis-logs");
			System.setProperty("logdir", String.valueOf(logDir.toAbsolutePath()));
			if (!Files.exists(logDir))
				Files.createDirectories(logDir);
		}

		logger = LogManager.getLogger("genesis");
		System.out.println("Logging to "+getLoggerFileName());
		
		RPGFrameworkLoader.getCallback();
		logger.trace("START: init()------------------------------------");
		logger.info("Running Genesis version "+System.getProperty(Constants.KEY_APPLICATION_VERSION));
		logger.info("Running on "+System.getProperty("os.name")+ "/" + System.getProperty("os.version")+ "/" + System.getProperty("os.arch"));
		logger.info("Running under "+System.getProperty("java.vendor")+" Java "+System.getProperty("java.version")+" with JavaFX "+System.getProperty("javafx.version"));
		logger.info("Running with language "+Locale.getDefault().getLanguage());
	}

	//-------------------------------------------------------------------
	/**
	 * @see javafx.application.Application#init()
	 */
	@Override
	public void init() throws Exception {
		host = getHostServices();
		if (System.getProperty(Constants.KEY_APPLICATION_ID)==null)
			System.setProperty(Constants.KEY_APPLICATION_ID, "genesis");
		if (System.getProperty(Constants.KEY_PROFILE)==null)
			System.setProperty(Constants.KEY_PROFILE, "no-profile");
		
		installDir = getInstallationDirectory();
		System.out.println("Genesis installation directory: "+installDir);
		installDir = getUserInstallationDirectory();
		System.out.println("Genesis per user installation directory: "+installDir);
		System.setProperty(RPGFrameworkConstants.PROPERTY_INSTALLATION_DIRECTORY, installDir.toString());
		
		try {
			initDetectVersion();
			initSetupLogging();
			logger.info("Genesis installation directory: "+getInstallationDirectory());
			logger.info("Genesis per user installation directory: "+installDir);
		} catch (Throwable e) {
			if (logger!=null)
				logger.fatal("Error in init()",e);
			e.printStackTrace();
			System.exit(ExitCodes.ERROR_INIT_PHASE);
		} finally {
			logger.trace("STOP : init()------------------------------------");
		}
	}

	//-------------------------------------------------------------------
	private static String getLoggerFileName() {
		try {
			org.apache.logging.log4j.core.Logger loggerImpl = (org.apache.logging.log4j.core.Logger) logger;
			Appender appender = loggerImpl.getParent().getAppenders().get("fileLogger");
			if (appender==null) {
				System.err.println("Cannot detect logfile");
				return System.getProperty("logdir");
			}
			// Unfortunately, File is no longer an option to return, here.
			return ((RollingFileAppender) appender).getFileName();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		return System.getProperty("logdir");
	}

	//--------------------------------------------------------------------
	private void prepareConfigNodes(Preferences pref) {
		ConfigContainer cfgRoot = RPGFrameworkLoader.getInstance().getConfiguration();
		cfgGenesis = cfgRoot.createContainer("genesis");
		cfgGenesis.changePreferences(pref);
		cfgGenesis.setResourceBundle((PropertyResourceBundle) RES);
		ConfigContainer cfgJFX     = cfgRoot.createContainer("jfx");
		cfgJFX.changePreferences(Preferences.userRoot().node("/org/prelle/jfx"));
		cfgJFX.setResourceBundle((PropertyResourceBundle) RES);

		//		cfgGenesis.createOption("installDir", Type.DIRECTORY, null);
		cfgSecondaryScreen = cfgGenesis.createOption(Constants.PREFKEY_USE_SECONDARY_SCREEN, Type.BOOLEAN, false);
		cfgGenesis.createOption(Constants.PREFKEY_FULLSCREEN, Type.BOOLEAN, true);
		//		cfgGenesis.createOption("promoDir", Type.DIRECTORY, null);

	}

	//-------------------------------------------------------------------
	/**
	 * @see javafx.application.Application#start(javafx.stage.Stage)
	 */
	@Override
	public void start(Stage stage) throws Exception {
		logger.debug("START: start()-----------------------------------");
		try {
			startSplash(stage);
			//		startFinal(stage);
		} finally {
			logger.debug("STOP : start()-----------------------------------");
		}
	}
	//

	//-------------------------------------------------------------------
	private void startSplash(Stage stage) throws Exception {
		GenesisSplashScreen stack = new GenesisSplashScreen();
		currentCallback = stack;
		Scene scene = new Scene(stack, 735,530,Color.TRANSPARENT);

		stage.setScene(scene);
		stage.initStyle(StageStyle.TRANSPARENT);
		stage.show();

		manager = new ScreenManager();
//		manager.setSettingsEnabled(true);
//		manager.setOnSettingsAction(event -> settingsClicked());
		
		/*
		 * Initialize RPGFramework
		 */
		Runnable toRun = new Runnable() {
			public void run() {
				logger.debug("START: startSplash.run()-------------------------");
				RPGFrameworkInitCallback callback = new RPGFrameworkInitCallback() {

					@Override
					public void progressChanged(double value) {
						logger.trace("Progress "+value);
						Genesis5Main.this.notifyPreloader(new Preloader.ProgressNotification(value));
						currentCallback.progressChanged(value);
					}

					@Override
					public void message(String mess) {
						logger.debug(mess);
						currentCallback.message(mess);
					}

					@Override
					public void errorOccurred(String location, String detail, Throwable exception) {
						Genesis5Main.this.notifyPreloader(new Preloader.ErrorNotification(location,detail,exception));
						currentCallback.errorOccurred(location, detail, exception);
					}

					@Override
					public void showConfigOptions(String id, List<ConfigOption<?>> configuration) {
						logger.info("showConfigOptions "+id);
						VBox box = new VBox(10);
						box.setStyle("-fx-padding: 1em");
						Label lblIntro = new Label();
						lblIntro.setText(ResourceI18N.get(RES, "bootstepOption."+id));
						lblIntro.setWrapText(true);
						lblIntro.setStyle("-fx-font-size: 150%");
						box.getChildren().add(lblIntro);

						if (StandardBootSteps.UPDATE_PLUGINS.name().equals(id)) {
							InstallPluginsNode content = new InstallPluginsNode();
							box.setStyle("-fx-pref-width: 43em; -fx-padding: 1em");
							box.getChildren().add(content);
						} else {

							Node lastOption = null;
							for (ConfigOption<?> opt : configuration) {
								@SuppressWarnings("unchecked")
								ConfigOption<Boolean> optBool = (ConfigOption<Boolean>)opt;
								CheckBox lbl = new CheckBox(opt.getName());
								lastOption = lbl;
								lbl.setSelected((Boolean)optBool.getValue());
								box.getChildren().add(lbl);
								lbl.setOnAction( ev -> optBool.set( lbl.isSelected()) );
							}
							if (lastOption!=null)
								VBox.setMargin(lastOption, new Insets(10,0,0,0));
						}
						
						Button okay = new Button("OK");
						okay.setMaxWidth(Double.MAX_VALUE);
						okay.setStyle("-fx-border-width: 1px;");
						okay.setAlignment(Pos.CENTER);
						box.getChildren().add(okay);
						VBox.setMargin(okay, new Insets(10,40,10,40));
						VBox.setVgrow(okay, Priority.ALWAYS);
						synchronized (manager) {
							Platform.runLater( () -> {
								logger.warn("GO");
								Scene scene = new Scene(box);
								scene.getStylesheets().add(Genesis5Main.class.getResource("css/genesis.css").toString());
								Stage stage = new Stage(StageStyle.DECORATED);
								okay.setOnAction( ev -> stage.hide());
								stage.setScene(scene);
								stage.showAndWait();
								//								manager.showAlertAndCall(AlertType.QUESTION, "Choice", box);
								synchronized (manager)  {
									manager.notify();
								}
							});
							try {
								manager.wait();
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
				};
				RPGFrameworkLoader.setCallback(callback);
				RPGFramework framework = RPGFrameworkLoader.getInstance();
				framework.addBootStep(StandardBootSteps.UPDATE_PLUGINS);
				framework.addBootStep(StandardBootSteps.ROLEPLAYING_SYSTEMS);
				framework.addBootStep(StandardBootSteps.PRODUCT_DATA);
				framework.addBootStep(StandardBootSteps.CHARACTERS);
				framework.addBootStep(new CheckForUpdates(logger));
//				framework.addBootStep(new CheckReleaseNotes(logger, Genesis5Main.this));
				logger.debug("initialize RPGFramework");
				framework.initialize(callback);

				Platform.runLater(new Runnable() {
					public void run() {
						logger.debug("START: run()-------------------------------------");
						try {
							stage.hide();
							startFinal(new Stage());
						} finally {
							logger.debug("STOP : run()-------------------------------------");
						}
					}
				});
				logger.debug("STOP : startSplash.run()-------------------------");
			}
		};
		Thread thread = new Thread(toRun);
		thread.start();
		logger.debug("presented splash screen");
	}

	//-------------------------------------------------------------------
	private void startFinal(Stage stage) {
		logger.debug("Genesis3Main.startFinal()");
		//		ModernUI.initialize();

		Preferences pref = 	Preferences.userRoot().node("/org/prelle/"+System.getProperty("application.id"));
		prepareConfigNodes(pref);
//		external.prepareConfigNodes(cfgGenesis);

		stage.initStyle(StageStyle.DECORATED);
		stage.setTitle("Genesis");
		stage.getIcons().add(
				new Image(Genesis5Main.class.getResourceAsStream( "images/icon.gif" )));
		stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			public void handle(WindowEvent we) {
				logger.info("Stage is closing upon user request");
				try {
					Genesis5Main.this.stop();
					System.exit(0);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					System.exit(0);
				}
			}
		});

		//		OnlineActivityWizard wizard = new OnlineActivityWizard(this);
		//		manager.show(wizard);

		MainScreen mainScr = new MainScreen(cfgGenesis);
		logger.debug("----------Call show() on ScreenManager");
		manager.navigateTo(mainScr);
		mainScr.refresh();

		Scene scene = new Scene(manager);
		stage.setScene(scene);
		stage.setWidth(Screen.getPrimary().getVisualBounds().getWidth());
		stage.setHeight(Screen.getPrimary().getVisualBounds().getHeight());
		stage.setY(0);
		stage.setX(0);
		ModernUI.initialize(scene);
//		scene.getStylesheets().add(RPGFrameworkJFXConstants.class.getResource("css/rpgframework.css").toExternalForm());
		scene.getStylesheets().add(Genesis5Main.class.getResource("css/genesis.css").toString());
		scene.getStylesheets().add(RPGFrameworkJFXConstants.STYLESHEET);

		/*
		 * Move window to secondary monitor
		 */
		logger.debug(Constants.PREFKEY_FULLSCREEN+" = "+pref.getBoolean(Constants.PREFKEY_FULLSCREEN, true));
		stage.setFullScreen(pref.getBoolean(Constants.PREFKEY_FULLSCREEN, false));
		if ((Boolean)cfgSecondaryScreen.getValue()) {
			for (Screen screen : Screen.getScreens()) {
				if (screen!=Screen.getPrimary()) {
					stage.setX(screen.getVisualBounds().getMinX());
					stage.setY(screen.getVisualBounds().getMinY());
				}
			}
		}

		// Close splash screen
		SplashScreen splash = SplashScreen.getSplashScreen();
		if (splash!=null)
			splash.close();

		stage.show();

		checkForPluginUpdates();
		checkForErrors();
		//        /*
		//         * Check for release notes to display. Send notification to preloader and get
		//         * it filled with release notes, if there are any
		//         */
		//        ReturnReleaseNotesNotification releaseNotify = new ReturnReleaseNotesNotification();
		//        notifyPreloader(releaseNotify);
		mainScr.greet();
	}

	//--------------------------------------------------------------------
	@SuppressWarnings("exports")
	public static HostServices getHostServicesDelegate() {
		return host;
	}

	//--------------------------------------------------------------------
	/**
	 * Eventually show information about updated plugins
	 */
	private void checkForPluginUpdates() {
		logger.debug("Check if plugins have been updated");
		PluginRegistry registry = RPGFrameworkLoader.getInstance().getPluginRegistry();
		List<PluginDescriptor> state = new ArrayList<PluginDescriptor>();
		for (PluginDescriptor plugin : registry.getKnownPlugins()) {
			logger.info("Plugin "+plugin.filename+"  \tupdated= "+plugin.result);
			if (plugin.result!=null)
				state.add(plugin);
		}
		
		if (!state.isEmpty()) {	
			Collections.sort(state);
			DateFormat FORMAT = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
			GridPane grid = new GridPane();
			grid.setStyle("-fx-vgap: 0.5em; -fx-hgap: 1em");
			int y=-1;
			for (PluginDescriptor plugin : state) {
				y++;
				grid.add(new Label(plugin.getName()), 0, y);
				grid.add(new Label(plugin.getVersion()+""), 1, y);
				grid.add(new Label(FORMAT.format(new Date(plugin.timestamp.toEpochMilli()))), 2, y);
			}
		
			VBox layout = new VBox(20);
			Label msg = new Label(ResourceI18N.get(RES, "dialog.updates.message"));
			msg.setWrapText(true);
			layout.getChildren().addAll(msg, grid);
			
			manager.showAlertAndCall(AlertType.NOTIFICATION, ResourceI18N.get(RES, "dialog.updates.title"), layout);
		}
		
	}

	//--------------------------------------------------------------------
	/**
	 * Eventually show information about updated plugins
	 */
	private void checkForErrors() {
		logger.debug("Check if errors have occurred");
		List<String> errors = RPGFrameworkLoader.getInstance().getUpdateErrors();
		
		if (!errors.isEmpty()) {	
			DateFormat FORMAT = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
			VBox grid = new VBox();
			grid.setStyle("-fx-vgap: 0.5em; -fx-hgap: 1em");
			int y=-1;
			for (String message : errors) {
				y++;
				grid.getChildren().add(new Label(message));
			}
		
			VBox layout = new VBox(20);
			Label msg = new Label(ResourceI18N.get(RES, "dialog.update_failures.message"));
			msg.setWrapText(true);
			layout.getChildren().addAll(msg, grid);
			
			manager.showAlertAndCall(AlertType.NOTIFICATION, ResourceI18N.get(RES, "dialog.update_failures.title"), layout);
		}
		
	}
}
