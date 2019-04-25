/*
 * Copyright © 2017-2019 Cask Data, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy of
 *  the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package io.cdap.re;

import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.plugin.PluginConfig;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.InvalidEntry;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.Transform;
import io.cdap.cdap.etl.api.TransformContext;
import io.cdap.directives.aggregates.DefaultTransientStore;
import io.cdap.wrangler.api.ExecutorContext;
import io.cdap.wrangler.api.Row;
import io.cdap.wrangler.api.TransientStore;
import io.cdap.wrangler.utils.RecordConvertor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Class description here.
 */
@Plugin(type = "transform")
@Name("RulesEngine")
@Description("A Rule Engine that uses Inference to determines the fields to process in a record")
public final class RulesEngine extends Transform<StructuredRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(RulesEngine.class);

  // Plugin configuration.
  private final Config config;

  // Inference Engine
  private InferenceEngine ie;

  // Transient Store
  private TransientStore store;

  // Output Schema associated with transform output.
  private Schema oSchema = null;

  // Rule book being processed.
  private Rulebook rulebook;

  // Converts record from row to StructuredRecord.
  private final RecordConvertor convertor = new RecordConvertor();

  // Output rows
  private final List<Row> rows = new ArrayList<>();

  public RulesEngine(Config config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer configurer)
    throws IllegalArgumentException {
    super.configurePipeline(configurer);

    try {
      if(!config.containsMacro("rulebook")) {
        Reader reader = new StringReader(config.rulebook);
        Compiler compiler = new RulebookCompiler();
        rulebook = compiler.compile(reader);
        InferenceEngine ie = new RuleInferenceEngine(rulebook, null);
        ie.initialize();
      }
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage());
    }

    // Based on the configuration create output schema.
    try {
      if (!config.containsMacro("schema")) {
        oSchema = Schema.parseJson(config.schema);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Format of output schema specified is invalid. Please check the format.");
    }

    // Set the output schema.
    if (oSchema != null) {
      configurer.getStageConfigurer().setOutputSchema(oSchema);
    }
  }

  @Override
  public void initialize(TransformContext context) throws Exception {
    super.initialize(context);

    store = new DefaultTransientStore();

    // Based on the configuration create output schema.
    try {
      oSchema = Schema.parseJson(config.schema);
    } catch (IOException e) {
      throw new IllegalArgumentException(
        String.format("Stage:%s - Format of output schema specified is invalid. Please check the format.",
                      context.getStageName())
      );
    }

    Reader reader = new StringReader(config.rulebook);
    Compiler compiler = new RulebookCompiler();
    rulebook = compiler.compile(reader);

    ExecutorContext ctx = new RulesEngineContext(ExecutorContext.Environment.TRANSFORM,
                                                 context, store);
    ie = new RuleInferenceEngine(rulebook, ctx);
    ie.initialize();
  }

  @Override
  public void transform(StructuredRecord input, Emitter<StructuredRecord> emitter)
    throws Exception {

    Row row = new Row();
    for (Schema.Field field : input.getSchema().getFields()) {
      row.add(field.getName(), input.get(field.getName()));
    }

    try {
      rows.clear();
      row = ie.infer(row);
      if (row != null) {
        rows.add(row);
        List<StructuredRecord> records = convertor.toStructureRecord(rows, oSchema);
        for (StructuredRecord record : records) {
          StructuredRecord.Builder builder = StructuredRecord.builder(oSchema);
          // Iterate through output schema, if the 'record' doesn't have it, then
          // attempt to take if from 'input'.
          for (Schema.Field field : oSchema.getFields()) {
            Object wObject = record.get(field.getName()); // wrangled records
            if (wObject == null) {
              builder.set(field.getName(), null);
            } else {
              if (wObject instanceof String) {
                builder.convertAndSet(field.getName(), (String) wObject);
              } else {
                builder.set(field.getName(), wObject);
              }
            }
          }
          emitter.emit(builder.build());
        }
      }
    } catch (SkipRowException e) {
      String message = String.format("Fired rulebook '%s', version '%s', rule name '%s', description '%s', condition {%s}.",
                                     rulebook.getName(), rulebook.getVersion(), e.getRule().getName(),
                                     e.getRule().getDescription(), e.getRule().getWhen());
      emitter.emitError(new InvalidEntry<>(100, message, input));
    }
  }

  public static class Config extends PluginConfig {
    @Name("rulebook")
    @Description("Specify the rule book.")
    @Macro
    private String rulebook;

    @Name("schema")
    @Description("Specifies the schema that has to be output.")
    @Macro
    private final String schema;

    @Name("rulebookid")
    @Description("Hidden property used only by UI")
    @Nullable
    private String rulebookid;

    public Config(String rulebook, String schema, String rulebookid) {
      this.rulebook = rulebook;
      this.schema = schema;
      this.rulebookid = rulebookid;
    }
  }
}
