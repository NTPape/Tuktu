package tuktu.api

import java.io.BufferedReader
import java.io.InputStreamReader
import com.netaporter.uri.Uri

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI

import scala.io.Codec
import scala.io.Source

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path

import com.netaporter.uri.Uri

object file {
    /**
     * A Generic Reader for multiple sources
     */
    def genericReader(uri: URI)(implicit codec: Codec): BufferedReader = {
        uri.getScheme match {
            case "file" | "" | null => fileReader(uri)
            case "hdfs"             => hdfsReader(uri)
            case _                  => throw new Exception("Unknown file format")
        }
    }

    /**
     * Wrapper for a string instead of URI
     */
    def genericReader(string: String)(implicit codec: Codec): BufferedReader = {
        genericReader(Uri.parse(string).toURI)(codec)
    }

    /**
     * Reads from Local disk
     */
    def fileReader(uri: URI)(implicit codec: Codec): BufferedReader = {
        Source.fromFile(uri.getPath)(codec).bufferedReader
    }

    /**
     * Reads from HDFS
     */
    def hdfsReader(uri: URI)(implicit codec: Codec): BufferedReader = {
        val conf = new Configuration
        conf.set("fs.defaultFS", uri.getHost + ":" + uri.getPort)
        val fs = FileSystem.get(conf)
        val path = new Path(uri.getPath)
        new BufferedReader(new InputStreamReader(fs.open(path), codec.decoder))
    }
}