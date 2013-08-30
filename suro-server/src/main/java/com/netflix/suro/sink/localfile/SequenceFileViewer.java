package com.netflix.suro.sink.localfile;

import com.netflix.suro.message.Message;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;

import java.io.IOException;

public class SequenceFileViewer {
    public static void main(String[] args) throws IOException {
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(conf);

        SequenceFile.Reader r = new SequenceFile.Reader(fs, new Path(args[0]), conf);
        Text routingKey = new Text();
        Message message = new Message();

        while (r.next(routingKey, message)) {
            System.out.println("###routing key: " + routingKey);
            System.out.println(message.getSerDe().toString(message.getPayload()));
        }

        r.close();
    }
}
