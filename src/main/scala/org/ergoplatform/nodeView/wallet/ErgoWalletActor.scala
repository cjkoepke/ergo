package org.ergoplatform.nodeView.wallet

import akka.actor.Actor
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.history.ErgoHistory.Height
import org.ergoplatform._
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.nodeView.state.ErgoStateContext
import org.ergoplatform.nodeView.wallet.BoxCertainty.{Certain, Uncertain}
import org.ergoplatform.settings.ErgoSettings
import scorex.core.{ModifierId, bytesToId}
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.ADDigest
import sigmastate.interpreter.ContextExtension
import sigmastate.{AvlTreeData, Values}

import scala.collection.Map
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global


case class BalancesSnapshot(height: Height, balance: Long, assetBalances: Map[ModifierId, Long])


class ErgoWalletActor(settings: ErgoSettings) extends Actor with ScorexLogging {

  import ErgoWalletActor._

  private lazy val seed = settings.walletSettings.seed

  private lazy val scanningInterval = settings.walletSettings.scanningInterval

  private val registry = new WalletStorage

  //todo: pass as a class argument, add to config
  val boxSelector: BoxSelector = DefaultBoxSelector

  private val prover = new ErgoProvingInterpreter(seed)

  private var height = 0
  private var lastBlockUtxoRootHash = ADDigest @@ Array.fill(32)(0: Byte)

  private val trackedAddresses: mutable.Buffer[ErgoAddress] =
    mutable.Buffer(prover.dlogPubkeys: _ *).map(P2PKAddress.apply)

  private val trackedBytes: mutable.Buffer[Array[Byte]] = trackedAddresses.map(_.contentBytes)

  //todo: make resolveUncertainty(boxId, witness)
  private def resolveUncertainty(): Unit = {
    registry.nextUncertain().foreach { uncertainBox =>
      val box = uncertainBox.box

      val lastUtxoDigest = AvlTreeData(lastBlockUtxoRootHash, 32)

      val testingTx = UnsignedErgoLikeTransaction(
        IndexedSeq(new UnsignedInput(box.id)),
        IndexedSeq(new ErgoBoxCandidate(1L, Values.TrueLeaf))
      )

      val context =
        ErgoLikeContext(height + 1, lastUtxoDigest, IndexedSeq(box), testingTx, box, ContextExtension.empty)

      prover.prove(box.proposition, context, testingTx.messageToSign) match {
        case Success(_) =>
          log.info(s"Uncertain box is mine! $uncertainBox")
          registry.makeTransitionTo(uncertainBox.copy(certainty = Certain))
        case Failure(_) =>
        //todo: remove after some time? remove spent after some time?
      }
    }
  }

  def scan(tx: ErgoTransaction, heightOpt: Option[Height]): Boolean = {
    tx.inputs.foreach { inp =>
      val boxId = bytesToId(inp.boxId)
      registry.makeTransition(boxId, ProcessSpending(tx, heightOpt))
    }

    tx.outputCandidates.zipWithIndex.count { case (outCandidate, outIndex) =>
      trackedBytes.find(t => outCandidate.propositionBytes.containsSlice(t)) match {
        case Some(_) =>
          val idxShort = outIndex.toShort
          val box = outCandidate.toBox(tx.serializedId, idxShort)
          val trackedBox = TrackedBox(tx, idxShort, heightOpt, box, Uncertain)
          registry.register(trackedBox)
          true
        case None =>
          false
      }
    } > 0
  }

  private def extractFromBlock(fb: ErgoFullBlock): Int = {
    height = fb.header.height
    lastBlockUtxoRootHash = fb.header.stateRoot
    fb.transactions.count(tx => scan(tx, Some(height)))
  }

  def scanLogic: Receive = {
    case ScanOffchain(tx) =>
      if (scan(tx, None)) {
        self ! Resolve
      }

    case Resolve =>
      resolveUncertainty()
      //todo: use non-default executor?
      if (registry.uncertainExists) {
        context.system.scheduler.scheduleOnce(scanningInterval)(self ! Resolve)
      }

    case ScanOnchain(fullBlock) =>
      val txsFound = extractFromBlock(fullBlock)
      (1 to txsFound).foreach(_ => self ! Resolve)

    //todo: update utxo root hash
    case Rollback(heightTo) =>
      height.until(heightTo, -1).foreach { h =>
        val toRemove = registry.confirmedAt(h)
        toRemove.foreach(boxId => registry.makeTransition(boxId, ProcessRollback(heightTo)))
      }
      height = heightTo
  }

  protected def generateTransactionWithOutputs(payTo: Seq[ErgoBoxCandidate]): Try[ErgoTransaction] = Try {
    require(prover.dlogPubkeys.nonEmpty, "No public keys in the prover to extract change address from")
    require(payTo.forall(_.value > 0), "Non-positive Ergo value")
    require(payTo.forall(_.additionalTokens.forall(_._2 > 0)), "Non-positive asset value")

    val targetBalance = payTo.map(_.value).sum

    val targetAssets = mutable.Map[ModifierId, Long]()

    /* todo: uncomment when sigma-state dependency will be updated from 0.9.5-SNAPSHOT
  payTo.map(_.additionalTokens).foreach { boxTokens =>
    AssetUtils.mergeAssets(targetAssets, boxTokens.map(t => bytesToId(t._1) -> t._2).toMap)
  } */

    //we currently do not use off-chain boxes to create a transaction
    def filterFn(trackedBox: TrackedBox) = trackedBox.onchainStatus.onchain

    boxSelector.select(registry.unspentBoxesIterator, filterFn, targetBalance, targetAssets.toMap).flatMap { r =>
      val inputs = r.boxes.toIndexedSeq

      val changeAddress = prover.dlogPubkeys(Random.nextInt(prover.dlogPubkeys.size))

      val changeBoxCandidates = r.changeBoxes.map { case (chb, cha) =>

        // todo: uncomment when sigma-state dependency will be updated from 0.9.5-SNAPSHOT
        val assets = IndexedSeq() //cha.map(t => Digest32 @@ idToBytes(t._1) -> t._2).toIndexedSeq

        new ErgoBoxCandidate(chb, changeAddress, assets)
      }

      val unsignedTx = new UnsignedErgoTransaction(
        inputs.map(_.id).map(id => new UnsignedInput(id)),
        (payTo ++ changeBoxCandidates).toIndexedSeq)

      prover.sign(unsignedTx, inputs, ErgoStateContext(height, lastBlockUtxoRootHash)).toOption
    } match {
      case Some(tx) => tx
      case None     => throw new Exception(s"No enough boxes to assemble a transaction for $payTo")
    }
  }


  override def receive: Receive = scanLogic orElse {
    case WatchFor(address) =>
      trackedAddresses.append(address)
      trackedBytes.append(address.contentBytes)

    case ReadBalances(confirmed) =>
      if (confirmed) {
        sender() ! BalancesSnapshot(height, registry.confirmedBalance, registry.confirmedAssetBalances)
      } else {
        sender() ! BalancesSnapshot(height, registry.unconfirmedBalance, registry.unconfirmedAssetBalances)
      }

    case ReadWalletAddresses =>
      sender() ! trackedAddresses.toIndexedSeq

    //generate a transaction paying to a sequence of boxes payTo
    case GenerateTransaction(payTo) =>
      sender() ! generateTransactionWithOutputs(payTo)
  }
}

object ErgoWalletActor {

  private[ErgoWalletActor] case object Resolve

  case class WatchFor(address: ErgoAddress)

  case class ScanOffchain(tx: ErgoTransaction)

  case class ScanOnchain(block: ErgoFullBlock)

  case class Rollback(height: Int)

  case class GenerateTransaction(payTo: Seq[ErgoBoxCandidate])

  case class ReadBalances(confirmed: Boolean)

  case object ReadWalletAddresses

}
