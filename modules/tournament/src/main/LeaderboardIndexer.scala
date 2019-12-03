package lila.tournament

import akka.stream.scaladsl._
import reactivemongo.akkastream.{ AkkaStreamCursor, cursorProducer }
import reactivemongo.api._
import reactivemongo.api.bson._

import lila.db.dsl._

private final class LeaderboardIndexer(
    tournamentRepo: TournamentRepo,
    pairingRepo: PairingRepo,
    playerRepo: PlayerRepo,
    leaderboardRepo: LeaderboardRepo
)(implicit mat: akka.stream.Materializer) {

  import LeaderboardApi._
  import BSONHandlers._

  def generateAll: Funit = leaderboardRepo.coll.delete.one($empty) >> {
    tournamentRepo.coll.ext.find(tournamentRepo.finishedSelect)
      .sort($sort desc "startsAt")
      .cursor[Tournament](ReadPreference.secondaryPreferred)
      .documentSource()
      .take(20_000)
      .mapAsyncUnordered(1)(generateTourEntries)
      .mapConcat(identity)
      .grouped(500)
      .mapAsyncUnordered(1) { entries =>
          saveEntries(entries) inject entries.size
      }
      .fold(0)((acc, nb) => acc + nb)
      .wireTap { nb =>
          if (nb % 5 == 0) logger.info(s"Generating leaderboards... $nb")
      }
      .to(Sink.ignore)
      .run
  }.void

  def indexOne(tour: Tournament): Funit =
    leaderboardRepo.coll.delete.one($doc("t" -> tour.id)) >>
      generateTourEntries(tour) flatMap saveEntries

  private def saveEntries(entries: Seq[Entry]): Funit =
    entries.nonEmpty ?? leaderboardRepo.coll.insert.many(
      entries.flatMap(BSONHandlers.leaderboardEntryHandler.writeOpt)
    ).void

  private def generateTourEntries(tour: Tournament): Fu[List[Entry]] = for {
    nbGames <- pairingRepo.countByTourIdAndUserIds(tour.id)
    players <- playerRepo.bestByTourWithRank(tour.id, nb = 9000, skip = 0)
  } yield players.flatMap {
    case RankedPlayer(rank, player) => for {
      perfType <- tour.perfType
      nb <- nbGames get player.userId
    } yield Entry(
      id = player._id,
      tourId = tour.id,
      userId = player.userId,
      nbGames = nb,
      score = player.score,
      rank = rank,
      rankRatio = Ratio(if (tour.nbPlayers > 0) rank.toDouble / tour.nbPlayers else 0),
      freq = tour.schedule.map(_.freq),
      speed = tour.schedule.map(_.speed),
      perf = perfType,
      date = tour.startsAt
    )
  }
}
