package com.lm.beam;

import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.sql.SqlTransform;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SerializableFunctions;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.*;
import javax.annotation.Nullable;
/**
 * @Author: limeng
 * @Date: 2019/7/9 16:45
 */
public class BeamSqlExample {
    public static void main(String[] args) {
        PipelineOptions options = PipelineOptionsFactory.create();
        // 显式指定PipelineRunner：DirectRunner（Local模式）
        options.setRunner(DirectRunner.class);

        Pipeline pipeline = Pipeline.create(options);

        Schema type =
                Schema.builder().addInt32Field("c1").addStringField("c2").addDoubleField("c3").build();

        Row row1 = Row.withSchema(type).addValues(1, "row", 1.0).build();
        Row row2 = Row.withSchema(type).addValues(2, "row", 2.0).build();
        Row row3 = Row.withSchema(type).addValues(3, "row", 3.0).build();

        PCollection<Row> inputTable = PBegin.in(pipeline).apply(Create.of(row1,row2,row3)
                .withSchema(type, SerializableFunctions.identity(), SerializableFunctions.identity()));

        PCollection<Row> outputStream =
                inputTable.apply(SqlTransform.query("select c1, c2, c3 from PCOLLECTION where c1 > 1"));

        outputStream.apply("",MapElements.via(
                new SimpleFunction<Row, Void>() {
                    @Override
                    public @Nullable Void apply(Row input) {
                        // expect output:
                        //  PCOLLECTION: [3, row, 3.0]
                        //  PCOLLECTION: [2, row, 2.0]
                        System.out.println("PCOLLECTION: " + input.getValues());
                        return null;
                    }
                }));
        PCollection<Row> outputStream2 = PCollectionTuple.of(new TupleTag<>("CASE1_RESULT"), outputStream)
                .apply(SqlTransform.query("select c2, sum(c3) from CASE1_RESULT group by c2"));


        outputStream2.apply(
                "log_result",
                MapElements.via(
                        new SimpleFunction<Row, Void>() {
                            @Override
                            public @Nullable Void apply(Row input) {
                                // expect output:
                                //  CASE1_RESULT: [row, 5.0]
                                System.out.println("CASE1_RESULT: " + input.getValues());
                                return null;
                            }
                        }));


        pipeline.run().waitUntilFinish();
    }
}
