package scorex.consensus.nxt

import com.google.common.primitives.Longs
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.block.{Block, BlockField}
import scorex.consensus.{ConsensusModule, LagonakiConsensusModule}
import scorex.crypto.hash.FastCryptographicHash._
import scorex.transaction._
import scorex.utils.{NTP, ScorexLogging}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Try}


class NxtLikeConsensusModule(AvgDelayInSeconds: Long = 60)
  extends LagonakiConsensusModule[NxtLikeConsensusBlockData] with ScorexLogging {

  import NxtLikeConsensusModule._

  implicit val consensusModule: ConsensusModule[NxtLikeConsensusBlockData] = this

  val version = 1: Byte

  override def isValid[TT](block: Block)(implicit transactionModule: TransactionModule[TT]): Boolean = Try {

    val history = transactionModule.blockStorage.history

    val blockTime = block.timestampField.value

    val prev = history.parent(block).get
    val prevTime = prev.timestampField.value

    val prevBlockData = consensusBlockData(prev)
    val blockData = consensusBlockData(block)
    val generator = block.signerDataField.value.generator

    //check baseTarget
    val cbt = calcBaseTarget(prev, blockTime)
    val bbt = blockData.baseTarget
    require(cbt == bbt, s"Block's basetarget is wrong, calculated: $cbt, block contains: $bbt")

    //check generation signature
    val calcGs = calcGeneratorSignature(prevBlockData, generator)
    val blockGs = blockData.generationSignature
    require(calcGs.sameElements(blockGs),
      s"Block's generation signature is wrong, calculated: ${calcGs.mkString}, block contains: ${blockGs.mkString}")

    //check hit < target
    calcHit(prevBlockData, generator) < calcTarget(prev, blockTime, effectiveBalance(generator))
  }.recoverWith { case t =>
    log.error("Error while checking a block", t)
    Failure(t)
  }.getOrElse(false)


  override def generateNextBlock[TT](account: PrivateKeyAccount)
                                    (implicit transactionModule: TransactionModule[TT]): Future[Option[Block]] = {

    val lastBlock = transactionModule.blockStorage.history.lastBlock
    val lastBlockKernelData = consensusBlockData(lastBlock)

    val lastBlockTime = lastBlock.timestampField.value

    val currentTime = NTP.correctedTime()
    val effBalance = effectiveBalance(account)

    val h = calcHit(lastBlockKernelData, account)
    val t = calcTarget(lastBlock, currentTime, effBalance)

    val eta = (currentTime - lastBlockTime) / 1000


    log.debug(s"hit: $h, target: $t, generating ${h < t}, eta $eta, " +
      s"account:  $account " +
      s"account balance: $effBalance"
    )

    if (h < t) {
      val btg = calcBaseTarget(lastBlock, currentTime)
      val gs = calcGeneratorSignature(lastBlockKernelData, account)
      val consensusData = new NxtLikeConsensusBlockData {
        override val generationSignature: Array[Byte] = gs
        override val baseTarget: Long = btg
      }

      val unconfirmed = transactionModule.packUnconfirmed()
      log.debug(s"Build block with ${unconfirmed.asInstanceOf[Seq[Transaction]].size} transactions")

      Future(Some(Block.buildAndSign(version,
        currentTime,
        lastBlock.uniqueId,
        consensusData,
        unconfirmed,
        account)))

    } else Future(None)
  }

  private def effectiveBalance[TT](account: Account)(implicit transactionModule: TransactionModule[TT]) =
    transactionModule.blockStorage.state.asInstanceOf[BalanceSheet].balanceWithConfirmations(account.address, EffectiveBalanceDepth)

  private def calcGeneratorSignature(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount) =
    hash(lastBlockData.generationSignature ++ generator.publicKey)

  private def calcHit(lastBlockData: NxtLikeConsensusBlockData, generator: PublicKeyAccount): BigInt =
    BigInt(1, calcGeneratorSignature(lastBlockData, generator).take(8).reverse)

  /**
    * BaseTarget calculation algorithm fixing the blocktimes.
    *
    */
  private def calcBaseTarget[TT](prevBlock: Block, timestamp: Long) (implicit transactionModule: TransactionModule[TT]): Long = {
    val height = transactionModule.blockStorage.history.heightOf(prevBlock).get
    val prevBaseTarget = consensusBlockData(prevBlock).baseTarget
    if (height % 2 == 0) {
      val lastBlocks: Seq[Block] = transactionModule.blockStorage.history.lastBlocks(3)
      val block = lastBlocks.head
      val blocktimeAverage = (timestamp - block.timestampField.value) / lastBlocks.size
      val bt =
        if (blocktimeAverage > AvgDelayInSeconds) {
          (prevBaseTarget * Math.min(blocktimeAverage, MaxBlocktimeLimit)) / AvgDelayInSeconds
        }
        else {
          prevBaseTarget - prevBaseTarget * BaseTargetGamma * (AvgDelayInSeconds - Math.max(blocktimeAverage, MinBlocktimeLimit)) / (AvgDelayInSeconds * 100)
        }
      if (bt < 0 || bt > MaxBaseTarget2) {
        MaxBaseTarget2
      } else if (bt < MinBaseTarget) {
        MinBaseTarget
      } else {
        bt
      }
    } else {
      prevBaseTarget
    }
  }

  private def calcTarget(prevBlock: Block,
                         timestamp: Long,
                         effBalance: Long)(implicit transactionModule: TransactionModule[_]): BigInt = {
    val prevBlockData = consensusBlockData(prevBlock)
    val prevBlockTimestamp = prevBlock.timestampField.value

    val eta = (timestamp - prevBlockTimestamp) / 1000 //in seconds
    BigInt(prevBlockData.baseTarget) * eta * effBalance
  }

  private def bounded(value: BigInt, min: BigInt, max: BigInt): BigInt =
    if (value < min) min else if (value > max) max else value

  override def parseBytes(bytes: Array[Byte]): Try[BlockField[NxtLikeConsensusBlockData]] = Try {
    NxtConsensusBlockField(new NxtLikeConsensusBlockData {
      override val baseTarget: Long = Longs.fromByteArray(bytes.take(BaseTargetLength))
      override val generationSignature: Array[Byte] = bytes.takeRight(GeneratorSignatureLength)
    })
  }

  override def blockScore(block: Block)(implicit transactionModule: TransactionModule[_]): BigInt = {
    val baseTarget = consensusBlockData(block).baseTarget
    BigInt("18446744073709551616") / baseTarget
  }.ensuring(_ > 0)

  override def generators(block: Block): Seq[Account] = Seq(block.signerDataField.value.generator)

  override def genesisData: BlockField[NxtLikeConsensusBlockData] =
    NxtConsensusBlockField(new NxtLikeConsensusBlockData {
      override val baseTarget: Long = 153722867
      override val generationSignature: Array[Byte] = Array.fill(32)(0: Byte)
    })

  override def formBlockData(data: NxtLikeConsensusBlockData): BlockField[NxtLikeConsensusBlockData] =
    NxtConsensusBlockField(data)

  override def consensusBlockData(block: Block): NxtLikeConsensusBlockData = block.consensusDataField.value match {
    case b: NxtLikeConsensusBlockData => b
    case m => throw new AssertionError(s"Only NxtLikeConsensusBlockData is available, $m given")
  }
}


object NxtLikeConsensusModule {
  val BaseTargetLength = 8
  val GeneratorSignatureLength = 32

  val MinBlocktimeLimit = 53
  val MaxBlocktimeLimit = 67
  val BaseTargetGamma = 64
  val InitialBaseTarget = 153722867
  val MaxBaseTarget2 = InitialBaseTarget * 50
  val MinBaseTarget = InitialBaseTarget * 9 / 10

  val EffectiveBalanceDepth = 1440
}
