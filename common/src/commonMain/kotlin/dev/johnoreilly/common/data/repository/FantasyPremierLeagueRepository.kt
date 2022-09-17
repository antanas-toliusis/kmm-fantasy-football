package dev.johnoreilly.common.data.repository

import dev.johnoreilly.common.data.remote.FantasyPremierLeagueApi
import dev.johnoreilly.common.domain.entities.GameFixture
import dev.johnoreilly.common.domain.entities.Player
import dev.johnoreilly.common.domain.entities.Team
import io.realm.Realm
import io.realm.RealmObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.CoroutineContext

class TeamDb: RealmObject {
    var id: Int = 0
    var index: Int = 0
    var name: String = ""
    var code: Int = 0
}

class PlayerDb: RealmObject {
    var id: Int = 0
    var firstName: String = ""
    var secondName: String = ""
    var code: Int = 0
    var teamCode: Int = 0
    var totalPoints: Int = 0
    var nowCost: Int = 0
    var goalsScored: Int = 0
    var assists: Int = 0
    var team: TeamDb? = null
}

class FixtureDb: RealmObject {
    var id: Int = 0
    var kickoffTime: String? = ""
    var homeTeam: TeamDb? = null
    var awayTeam: TeamDb? = null
    var homeTeamScore: Int = 0
    var awayTeamScore: Int = 0
}

class FantasyPremierLeagueRepository : KoinComponent {
    private val fantasyPremierLeagueApi: FantasyPremierLeagueApi by inject()
    private val mainScope: CoroutineScope = MainScope()

    private val _teamList = MutableStateFlow<List<Team>>(emptyList())
    val teamList = _teamList.asStateFlow()

    private val _playerList = MutableStateFlow<List<Player>>(emptyList())
    val playerList = _playerList.asStateFlow()

    private val _fixtureList = MutableStateFlow<List<GameFixture>>(emptyList())
    val fixtureList = _fixtureList.asStateFlow()

    private val realm: Realm by inject()

    init {
        mainScope.launch {
            loadData()

            realm.objects(TeamDb::class).observe {
                _teamList.value = it.toList().map {
                    Team(it.id, it.index, it.name, it.code)
                }
            }

            realm.objects(PlayerDb::class).observe {
                _playerList.value = it.toList().map {
                    val playerName = "${it.firstName} ${it.secondName}"
                    val playerImageUrl = "https://resources.premierleague.com/premierleague/photos/players/110x140/p${it.code}.png"
                    val teamName = it.team?.name ?: ""
                    val currentPrice = it.nowCost / 10.0

                    Player(it.id, playerName, teamName, playerImageUrl, it.totalPoints, currentPrice, it.goalsScored, it.assists)
                }
            }

            realm.objects(FixtureDb::class).observe {
                _fixtureList.value = it.toList().map {
                    val homeTeamName = it.homeTeam?.name ?: ""
                    val homeTeamCode = it.homeTeam?.code ?: 0
                    val homeTeamScore = it.homeTeamScore ?: 0
                    val homeTeamPhotoUrl = "https://resources.premierleague.com/premierleague/badges/t${homeTeamCode}.png"

                    val awayTeamName = it.awayTeam?.name ?: ""
                    val awayTeamCode = it.awayTeam?.code ?: 0
                    val awayTeamScore = it.awayTeamScore ?: 0
                    val awayTeamPhotoUrl = "https://resources.premierleague.com/premierleague/badges/t${awayTeamCode}.png"

                    it.kickoffTime?.let { kickoffTime ->
                        val localKickoffTime = kickoffTime.toInstant().toLocalDateTime(TimeZone.currentSystemDefault())

                        GameFixture(it.id, localKickoffTime, homeTeamName, awayTeamName,
                            homeTeamPhotoUrl, awayTeamPhotoUrl, homeTeamScore, awayTeamScore)
                    }
                }.filterNotNull()
            }
        }
    }

    private suspend fun loadData() {
        val bootstrapStaticInfoDto = fantasyPremierLeagueApi.fetchBootstrapStaticInfo()
        val fixtures = fantasyPremierLeagueApi.fetchFixtures()

        realm.writeBlocking {

            // basic implementation for now where we recreate/repopulate db on startup
            realm.objects<TeamDb>().delete()
            realm.objects<PlayerDb>().delete()
            realm.objects<FixtureDb>().delete()

            // store teams
            bootstrapStaticInfoDto.teams.forEachIndexed { teamIndex, teamDto ->
                copyToRealm(TeamDb().apply {
                    id = teamDto.id
                    index = teamIndex + 1
                    name = teamDto.name
                    code = teamDto.code
                })
            }

            // store players
            bootstrapStaticInfoDto.elements.forEach { player ->
                copyToRealm(PlayerDb().apply {
                    id = player.id
                    firstName = player.first_name
                    secondName = player.second_name
                    code = player.code
                    teamCode = player.team_code
                    totalPoints = player.total_points
                    nowCost = player.now_cost
                    goalsScored = player.goals_scored
                    assists = player.assists

                    team = realm.objects<TeamDb>().query("code = $0", player.team_code).first()
                })
            }

            // store fixtures
            val teams = realm.objects<TeamDb>().toList()
            fixtures.forEach { fixtureDto ->
                if (fixtureDto.kickoff_time != null) {
                    copyToRealm(FixtureDb().apply {
                        id = fixtureDto.id
                        kickoffTime = fixtureDto.kickoff_time
                        fixtureDto.team_h_score?.let { homeTeamScore = it }
                        fixtureDto.team_a_score?.let { awayTeamScore = it }

                        homeTeam = teams.find { it.index == fixtureDto.team_h }
                        awayTeam = teams.find { it.index == fixtureDto.team_a }
                    })
                }
            }

        }
    }

    suspend fun fetchPastFixtures() = fantasyPremierLeagueApi
        .fetchFixtures()
        .filter { it.kickoff_time != null && it.team_h_score != null && it.team_a_score != null}


    // called from iOS
    fun getPlayers(success: (List<Player>) -> Unit) {
        mainScope.launch {
            playerList.collect {
                success(it.sortedByDescending { it.points })
            }
        }
    }

    val iosScope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = SupervisorJob() + Dispatchers.Main
    }

    fun getPlayersFlow() = KotlinNativeFlowWrapper<List<Player>>(playerList)


    fun getFixtures(success: (List<GameFixture>) -> Unit) {
        mainScope.launch {
            fixtureList.collect {
                success(it)
            }
        }
    }
}


class KotlinNativeFlowWrapper<T>(private val flow: Flow<T>) {
    fun subscribe(
        scope: CoroutineScope,
        onEach: (item: T) -> Unit,
        onComplete: () -> Unit,
        onThrow: (error: Throwable) -> Unit
    ) = flow
        .onEach { onEach(it) }
        .catch { onThrow(it) }
        .onCompletion { onComplete() }
        .launchIn(scope)
}
