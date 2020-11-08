package anilist.recsystem

import play.api.libs.json._
import scala.util.Try
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.streaming.Trigger

object Upload2Kafka {
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    val spark = {
        SparkSession
            .builder()
            .config("spark.master", "local[1]")
            .getOrCreate()
        }
    //spark.conf.set("spark.conf_dir.kafka.location","/home/gazavat/git/AnimeRecService/kafka")
    val kafka_dir_location = spark.conf.get("spark.conf_dir.kafka.location")
    def killAll() = {
            SparkSession
            .active
            .streams
            .active
            .foreach { x =>
                        val desc = x.lastProgress.sources.head.description
                        x.stop
                        println(s"Stopped ${desc}")
            }               
        }

    def createConsoleSink(df: DataFrame, 
                              fileName: String) = {
            df
            .writeStream
            .format("console")
            .option("truncate", "true")
            .option("checkpointLocation", s"chk/$fileName")
            .option("numRows", "10")
            .trigger(Trigger.ProcessingTime("10 seconds"))
        }
    
    def createSink(chkName: String, df: DataFrame)(batchFunc: (DataFrame,Long) => Unit) = {
        df
        .writeStream
        .trigger(Trigger.ProcessingTime("10 seconds"))
        .option("checkpointLocation", s"chk/$chkName")
        .foreachBatch(batchFunc)
    }

    def readFile(path: String) = {
        val source = scala.io.Source.fromFile(path)
        try source.mkString finally source.close()
    }

    def getKafkaParams(conf_file: String) = {
        Try({
            val lines = readFile(conf_file)
            lines.split("\n").map(line => {
                val arr = line.split("\\s+")
                arr(0) -> arr(1)
            }).toMap
        }).getOrElse(
            throw new Exception("Kafka conf file is missing or has incorrect format")
        )
    }

    def upload2KafkaMediaInfo() = {
        //
        spark.conf.set("spark.mediaProcess.reachedEnd",false)
        val kafkaParams = getKafkaParams(s"${kafka_dir_location}/kafka_params_upload_media.conf")
        val max_id = readFile(s"${kafka_dir_location}/boundaries.conf").split("\n").map(x => {
                val arr = x.split("\\s+")
                arr(0) -> arr(1).toInt
            }).toMap.getOrElse("media",1000)
        val udfGetMediaInfo = udf { (value: Int) => anilist.recsystem.CollectJsonInfo.collectMediaInfo(value) }
        val streamInfo = {
            spark.readStream
                 .format("rate")
                 .option("rowsPerSecond", "1")
                 .option("rampUpTime", "5")
                 .option("numPartitions", "1")
                 .load()
                 .withColumn("data",when(col("value")%lit(10)===0,udfGetMediaInfo((col("value")/lit(10)).cast("int")))
                                   .otherwise("""{"errors":"""))
                 .withColumn("id",(col("value")/lit(10)).cast("int"))
                 .select("id","data")
            }
        //createConsoleSink(streamInfo,"console3").start
        //spark.read.format("kafka").options(Map("kafka.bootstrap.servers" -> "localhost:9092","subscribe" ->"test_topic","startingOffsets" -> "earliest")).load
        val streamingDF = createSink("media", streamInfo) {
             (df, id) => 
            //println(df.count)
            //df.show(false)
            //println(s"This is batch $id")

            df.filter(!col("data").startsWith("""{"errors":"""))
              .select("data")
              .withColumnRenamed("data","value")
              .write
              .format("kafka")
              .options(kafkaParams)
              .save

            val max_curr_id = df.select("id").agg(coalesce(max("id"),lit(0)).as("id")).collect().map(x => x.getInt(0))

            val curr_id = Try(max_curr_id(0)).getOrElse(0)

            if (max_id <= curr_id) {spark.conf.set("spark.mediaProcess.reachedEnd",true)}

        }
        val startedStream = streamingDF.start
        //startedStream.awaitTermination
        while(startedStream.isActive) {
            if (spark.conf.get("spark.mediaProcess.reachedEnd").toBoolean) {
                startedStream.stop()
                val directory = new scala.reflect.io.Directory(new java.io.File("chk/media"))
                directory.deleteRecursively()
            } else {
            // wait 10 seconds before checking again if work is complete
                startedStream.awaitTermination(10000)
            }
        }
    }

    def upload2KafkaUserInfo() = {
        //
        spark.conf.set("spark.userProcess.reachedEnd",false)
        val kafkaParams = getKafkaParams(s"${kafka_dir_location}/kafka_params_upload_user.conf")
        val max_id = readFile(s"${kafka_dir_location}/boundaries.conf").split("\n").map(x => {
                val arr = x.split("\\s+")
                arr(0) -> arr(1).toInt
            }).toMap.getOrElse("user",1000)
        val udfGetUserAnimeInfo = udf { (value: Int) => anilist.recsystem.CollectJsonInfo.collectUserInfo(value,"ANIME") }
        val udfGetUserMangaInfo = udf { (value: Int) => anilist.recsystem.CollectJsonInfo.collectUserInfo(value,"MANGA") }
        val streamInfo = {
            spark.readStream
                 .format("rate")
                 .option("rowsPerSecond", "1")
                 .option("rampUpTime", "5")
                 .option("numPartitions", "1")
                 .load()
                 .withColumn("anime",when(col("value")%lit(10)===0,udfGetUserAnimeInfo((col("value")/lit(10)).cast("int")))
                                   .otherwise("""{"errors":"""))
                 .withColumn("manga",when(col("value")%lit(10)===0,udfGetUserMangaInfo((col("value")/lit(10)).cast("int")))
                                   .otherwise("""{"errors":"""))
                 .withColumn("id",(col("value")/lit(10)).cast("int"))
                 .select("id","anime","manga")
            }
        //createConsoleSink(streamInfo,"console3").start
        //spark.read.format("kafka").options(Map("kafka.bootstrap.servers" -> "localhost:9092","subscribe" ->"test_topic","startingOffsets" -> "earliest")).load
        val streamingDF = createSink("user", streamInfo) {
             (df, id) => 
            println(df.count)
            df.show()
            println(s"This is batch $id")

            df.filter((!col("anime").startsWith("""{"errors":"""))||(!col("manga").startsWith("""{"errors":""")))
              .select("anime","manga")
              .withColumn("value",concat(lit("""{"anime":"""),col("anime"),lit(""","manga": """),col("manga"),lit("}")))
              .select("value")
              .write
              .format("kafka")
              .options(kafkaParams)
              .save

            val max_curr_id = df.select("id").agg(coalesce(max("id"),lit(0)).as("id")).collect().map(x => x.getInt(0))

            val curr_id = Try(max_curr_id(0)).getOrElse(0)

            if (max_id <= curr_id) {spark.conf.set("spark.userProcess.reachedEnd",true)}

        }
        val startedStream = streamingDF.start
        //startedStream.awaitTermination
        while(startedStream.isActive) {
            if (spark.conf.get("spark.userProcess.reachedEnd").toBoolean) {
                startedStream.stop()
                val directory = new scala.reflect.io.Directory(new java.io.File("chk/user"))
                directory.deleteRecursively()
            } else {
            // wait 10 seconds before checking again if work is complete
                startedStream.awaitTermination(10000)
            }
        }
    }

    def main(args: Array[String]): Unit = {
        val mode = Try(args(0)).getOrElse(throw new Exception("you should declare mode"))
        mode match {
            case "media" => upload2KafkaMediaInfo()
            case "user"  => upload2KafkaUserInfo()
            case _       => throw new Exception("incorrect mode")
        }
    }

}