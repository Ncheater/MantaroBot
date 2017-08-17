package net.kodehawa.mantarobot;

import com.google.common.eventbus.EventBus;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.Guild;
import net.kodehawa.mantarobot.commands.moderation.TempBanManager;
import net.kodehawa.mantarobot.commands.music.MantaroAudioManager;
import net.kodehawa.mantarobot.core.LoadState;
import net.kodehawa.mantarobot.core.MantaroCore;
import net.kodehawa.mantarobot.core.listeners.events.PostLoadEvent;
import net.kodehawa.mantarobot.core.processor.DefaultCommandProcessor;
import net.kodehawa.mantarobot.core.shard.MantaroShard;
import net.kodehawa.mantarobot.core.shard.ShardedMantaro;
import net.kodehawa.mantarobot.core.shard.jda.ShardedJDA;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.log.SimpleLogToSLF4JAdapter;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.utils.CompactPrintStream;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.data.ConnectionWatcherDataManager;
import net.kodehawa.mantarobot.utils.rmq.RabbitMQDataManager;
import net.kodehawa.mantarobot.web.MantaroAPI;
import net.kodehawa.mantarobot.web.MantaroAPISender;
import okhttp3.*;
import org.apache.commons.collections4.iterators.ArrayIterator;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static net.kodehawa.mantarobot.utils.ShutdownCodes.API_HANDSHAKE_FAILURE;
import static net.kodehawa.mantarobot.utils.ShutdownCodes.FATAL_FAILURE;

/**
 * <pre>Main class for MantaroBot.</pre>
 *
 * <pre>This class could be considered a wrapper and a main initializer if you would like.</pre>
 *
 * This class contains all the methods and variables necessary for the main component of the bot.
 * Mantaro is modular, which means you technically could add more modules to /commands without the necessity to even touch this class. This also means you can remove modules
 * without major problems.
 *
 * <pre>This class and most classes check for a status of {@link LoadState#POSTLOAD} to start doing any JDA-related work, to avoid stacktraces and unwanted results.</pre>
 *
 * A instance of this class contains most of the necessary wrappers to make a command and JDA lookups. (see ShardedJDA and UnifiedJDA). All shards come to an unifying point
 * in this class, meaning that doing {@link MantaroBot#getUserById(String)} is completely valid and so it will look for all users in all shards, without duplicates (distinct).
 *
 * After JDA startup, the internal {@link EventBus} will attempt to dispatch {@link PostLoadEvent} to all the Module classes which contain a onPostLoad method, with a
 * {@link com.google.common.eventbus.Subscribe} annotation on it.
 *
 * Mantaro's version is determined, for now, on the data set in build.gradle and the date of build.
 *
 * This bot contains some mechanisms to prevent clones, such as some triggers to avoid bot start on incorrect settings, or just no timeout on database connection.
 * If you know about coding, I'm sure you could setup a instance of this bot without any problems and play around with it, but I would appreciate if you could keep all exact
 * or close clones of Mantaro outside of bot listing sites, since it will just get deleted from there (as in for clones of any other bot).
 * Thanks.
 *
 * <pr>This bot is a copyrighted work of Kodehawa and is the result a collaborative effort with AdrianTodt and many others,
 * This program is licensed under GPLv3, which summarized legal notice can be found down there.</pr>
 *
 * <pr>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.</pr>
 *
 * @see ShardedJDA
 * @see net.kodehawa.mantarobot.core.shard.jda.UnifiedJDA
 * @see Module
 * @since 16/08/2016
 * @author Kodehawa, AdrianTodt
 */
@Slf4j
public class MantaroBot extends ShardedJDA {

	public static int cwport;
	@Getter
	private final ShardedMantaro shardedMantaro;
	private static boolean DEBUG = false;
	@Getter
	private static MantaroBot instance;
	@Getter
	private static TempBanManager tempBanManager;
	@Getter
	private final MantaroAPI mantaroAPI = new MantaroAPI();
	@Getter
	private final RabbitMQDataManager rabbitMQDataManager;
	@Getter
	private static ConnectionWatcherDataManager connectionWatcher;
	@Getter
	private final MantaroAudioManager audioManager;
	@Getter
	private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);
	@Getter
	private final StatsDClient statsClient;
	@Getter
	private final MantaroCore core;


	public static void main(String[] args) {
		if (System.getProperty("mantaro.verbose") != null) {
			System.setOut(new CompactPrintStream(System.out));
			System.setErr(new CompactPrintStream(System.err));
		}

		if(System.getProperty("mantaro.debug") != null) {
			DEBUG = true;
			System.out.println("Running in debug mode!");
		}

		if (args.length > 0) {
			try {
				cwport = Integer.parseInt(args[0]);
			} catch (Exception e) {
				log.info("Invalid connection watcher port specified in arguments, using value in config");
				cwport = MantaroData.config().get().connectionWatcherPort;
			}
		} else {
			log.info("No connection watcher port specified, using value in config");
			cwport = MantaroData.config().get().connectionWatcherPort;
		}

		log.info("Using port " + cwport + " to communicate with connection watcher");

		if (cwport > 0) {
			new Thread(() -> {
				try {
					connectionWatcher = MantaroData.connectionWatcher();
				} catch (Exception e) {
					//Don't log this to sentry!
					log.error("Error connecting to Connection Watcher", e);
				}
			});
		}

		try {
			new MantaroBot();
		} catch (Exception e) {
			SentryHelper.captureException("Couldn't start Mantaro at all, so something went seriously wrong", e, MantaroBot.class);
			log.error("Could not complete Main Thread routine!", e);
			log.error("Cannot continue! Exiting program...");
			System.exit(FATAL_FAILURE);
		}
	}

	private MantaroBot() throws Exception {
        instance = this;
		Config config = MantaroData.config().get();
		core = new MantaroCore(config, true, true, DEBUG);

        statsClient = new NonBlockingStatsDClient(
				config.isPremiumBot() ? "mantaro-patreon" : "mantaro",
				"localhost",
				8125,
				"tag:value"
		);

		if(!config.isPremiumBot() && !config.isBeta() && !mantaroAPI.configure()) {
			SentryHelper.captureMessage("Cannot send node data to the remote server or ping timed out. Mantaro will exit", MantaroBot.class);
			System.exit(API_HANDSHAKE_FAILURE);
		}

		LogUtils.log("Startup", String.format("Starting up MantaroBot %s (Node ID: %s)", MantaroInfo.VERSION, mantaroAPI.nodeUniqueIdentifier));

		rabbitMQDataManager = new RabbitMQDataManager(config);
		if(!config.isPremiumBot() && !config.isBeta()) sendSignal();
		long start = System.currentTimeMillis();


		SimpleLogToSLF4JAdapter.install();

		core.setCommandsPackage("net.kodehawa.mantarobot.commands")
				.setOptionsPackage("net.kodehawa.mantarobot.options")
				.startMainComponents(false);

			shardedMantaro = core.getShardedInstance();
		audioManager = new MantaroAudioManager();
		tempBanManager = new TempBanManager(MantaroData.db().getMantaroData().getTempBans());

		System.out.println("[-=-=-=-=-=- MANTARO STARTED -=-=-=-=-=-]");

		MantaroData.config().save();

		log.info("Starting update managers...");
		shardedMantaro.startUpdaters();

		core.markAsReady();
		long end = System.currentTimeMillis();

		System.out.println("Finished loading basic components. Current status: " + MantaroCore.getLoadState());

		LogUtils.log("Startup",
				String.format("Loaded %d commands in %d shards. I woke up in %d seconds.",
						DefaultCommandProcessor.REGISTRY.commands().size(), shardedMantaro.getTotalShards(), (end - start) / 1000));

		if(!config.isPremiumBot() && !config.isBeta() ) {
			mantaroAPI.startService();
			MantaroAPISender.startService();
		}
	}

	public Guild getGuildById(String guildId) {
		return getShardForGuild(guildId).getGuildById(guildId);
	}

	public MantaroShard getShard(int id) {
		return Arrays.stream(shardedMantaro.getShards()).filter(shard -> shard.getId() == id).findFirst().orElse(null);
	}

	@Override
	public int getShardAmount() {
		return shardedMantaro.getTotalShards();
	}

	@Nonnull
	@Override
	public Iterator<JDA> iterator() {
		return new ArrayIterator<>(shardedMantaro.getShards());
	}

	public int getId(JDA jda) {
		return jda.getShardInfo() == null ? 0 : jda.getShardInfo().getShardId();
	}

	public MantaroShard getShardForGuild(String guildId) {
		return getShardForGuild(Long.parseLong(guildId));
	}

	public MantaroShard getShardForGuild(long guildId) {
		return getShard((int) ((guildId >> 22) % shardedMantaro.getTotalShards()));
	}

	public List<MantaroShard> getShardList() {
		return Arrays.asList(shardedMantaro.getShards());
	}

	private void sendSignal() {
		try{
			OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
			RequestBody body = RequestBody.create(
					MediaType.parse("application/json; charset=utf-8"),
					String.format("{\"content\": \"**Received startup trigger on Node %s", mantaroAPI.nodeUniqueIdentifier)
			);
			Request request = new Request.Builder()
					.header("Content-Type", "application/json")
					.post(body)
					.build();

			Response response = okHttpClient.newCall(request).execute();
			response.close();
		} catch (Exception ignored) {}
	}
}