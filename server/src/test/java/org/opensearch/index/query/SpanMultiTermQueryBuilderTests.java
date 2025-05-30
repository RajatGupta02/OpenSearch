/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.query;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.FieldMaskingSpanQuery;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopTermsRewrite;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.opensearch.common.compress.CompressedXContent;
import org.opensearch.common.lucene.search.SpanBooleanQueryRewriteWithMaxClause;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.lucene.queries.SpanMatchNoDocsQuery;
import org.opensearch.test.AbstractQueryTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singleton;
import static org.opensearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.startsWith;

public class SpanMultiTermQueryBuilderTests extends AbstractQueryTestCase<SpanMultiTermQueryBuilder> {
    @Override
    protected void initializeAdditionalMappings(MapperService mapperService) throws IOException {
        XContentBuilder mapping = jsonBuilder().startObject()
            .startObject("_doc")
            .startObject("properties")
            .startObject("prefix_field")
            .field("type", "text")
            .startObject("index_prefixes")
            .endObject()
            .endObject()
            .startObject("prefix_field_alias")
            .field("type", "alias")
            .field("path", "prefix_field")
            .endObject()
            .startObject("body")
            .field("type", "text")
            .endObject()
            .endObject()
            .endObject()
            .endObject();

        mapperService.merge("_doc", new CompressedXContent(mapping.toString()), MapperService.MergeReason.MAPPING_UPDATE);
    }

    @Override
    protected SpanMultiTermQueryBuilder doCreateTestQueryBuilder() {
        MultiTermQueryBuilder multiTermQueryBuilder = RandomQueryBuilder.createMultiTermQuery(random());
        return new SpanMultiTermQueryBuilder(multiTermQueryBuilder);
    }

    @Override
    protected void doAssertLuceneQuery(SpanMultiTermQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        if (query instanceof SpanMatchNoDocsQuery) {
            return;
        }
        assertThat(query, either(instanceOf(SpanMultiTermQueryWrapper.class)).or(instanceOf(FieldMaskingSpanQuery.class)));
        if (query instanceof SpanMultiTermQueryWrapper) {
            SpanMultiTermQueryWrapper wrapper = (SpanMultiTermQueryWrapper) query;
            Query innerQuery = queryBuilder.innerQuery().toQuery(context);
            if (queryBuilder.innerQuery().boost() != AbstractQueryBuilder.DEFAULT_BOOST) {
                assertThat(innerQuery, instanceOf(BoostQuery.class));
                BoostQuery boostQuery = (BoostQuery) innerQuery;
                innerQuery = boostQuery.getQuery();
            }
            assertThat(innerQuery, instanceOf(MultiTermQuery.class));
            MultiTermQuery multiQuery = (MultiTermQuery) innerQuery;
            if (multiQuery.getRewriteMethod() instanceof TopTermsRewrite) {
                assertThat(wrapper.getRewriteMethod(), instanceOf(SpanMultiTermQueryWrapper.TopTermsSpanBooleanQueryRewrite.class));
            } else {
                assertThat(wrapper.getRewriteMethod(), instanceOf(SpanBooleanQueryRewriteWithMaxClause.class));
            }
        } else if (query instanceof FieldMaskingSpanQuery) {
            FieldMaskingSpanQuery mask = (FieldMaskingSpanQuery) query;
            assertThat(mask.getMaskedQuery(), instanceOf(TermQuery.class));
        }
    }

    public void testIllegalArgument() {
        expectThrows(IllegalArgumentException.class, () -> new SpanMultiTermQueryBuilder((MultiTermQueryBuilder) null));
    }

    private static class TermMultiTermQueryBuilder implements MultiTermQueryBuilder {
        @Override
        public Query toQuery(QueryShardContext context) throws IOException {
            return new TermQuery(new Term("foo", "bar"));
        }

        @Override
        public QueryBuilder queryName(String queryName) {
            return this;
        }

        @Override
        public String queryName() {
            return "foo";
        }

        @Override
        public float boost() {
            return 1f;
        }

        @Override
        public QueryBuilder boost(float boost) {
            return this;
        }

        @Override
        public String getName() {
            return "foo";
        }

        @Override
        public String getWriteableName() {
            return "foo";
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {

        }

        @Override
        public String fieldName() {
            return "foo";
        }

        @Override
        public QueryBuilder filter(QueryBuilder filter) {
            return this;
        }
    }

    @Override
    protected boolean supportsBoost() {
        return false;
    }

    /**
     * test checks that we throw an {@link UnsupportedOperationException} if the query wrapped
     * by {@link SpanMultiTermQueryBuilder} does not generate a lucene {@link MultiTermQuery}.
     * This is currently the case for {@link RangeQueryBuilder} when the target field is mapped
     * to a date.
     */
    public void testUnsupportedInnerQueryType() throws IOException {
        MultiTermQueryBuilder query = new TermMultiTermQueryBuilder();
        SpanMultiTermQueryBuilder spanMultiTermQuery = new SpanMultiTermQueryBuilder(query);
        UnsupportedOperationException e = expectThrows(
            UnsupportedOperationException.class,
            () -> spanMultiTermQuery.toQuery(createShardContext())
        );
        assertThat(e.getMessage(), startsWith("unsupported inner query"));
    }

    public void testToQueryInnerSpanMultiTerm() throws IOException {
        Query query = new SpanOrQueryBuilder(createTestQueryBuilder()).toQuery(createShardContext());
        // verify that the result is still a span query, despite the boost that might get set (SpanBoostQuery rather than BoostQuery)
        assertThat(query, instanceOf(SpanQuery.class));
    }

    public void testToQueryInnerTermQuery() throws IOException {
        String fieldName = randomFrom("prefix_field", "prefix_field_alias");
        final QueryShardContext context = createShardContext();
        {
            Query query = new SpanMultiTermQueryBuilder(new PrefixQueryBuilder(fieldName, "foo")).toQuery(context);
            assertThat(query, instanceOf(FieldMaskingSpanQuery.class));
            FieldMaskingSpanQuery fieldQuery = (FieldMaskingSpanQuery) query;
            assertThat(fieldQuery.getMaskedQuery(), instanceOf(SpanTermQuery.class));
            assertThat(fieldQuery.getField(), equalTo("prefix_field"));
            SpanTermQuery termQuery = (SpanTermQuery) fieldQuery.getMaskedQuery();
            assertThat(termQuery.getTerm().field(), equalTo("prefix_field._index_prefix"));
            assertThat(termQuery.getTerm().text(), equalTo("foo"));
        }

        {
            Query query = new SpanMultiTermQueryBuilder(new PrefixQueryBuilder(fieldName, "f")).toQuery(context);
            assertThat(query, instanceOf(SpanMultiTermQueryWrapper.class));
            SpanMultiTermQueryWrapper wrapper = (SpanMultiTermQueryWrapper) query;
            assertThat(wrapper.getWrappedQuery(), instanceOf(PrefixQuery.class));
            assertThat(wrapper.getField(), equalTo("prefix_field"));
            PrefixQuery prefixQuery = (PrefixQuery) wrapper.getWrappedQuery();
            assertThat(prefixQuery.getField(), equalTo("prefix_field"));
            assertThat(prefixQuery.getPrefix().text(), equalTo("f"));
            assertThat(wrapper.getRewriteMethod(), instanceOf(SpanBooleanQueryRewriteWithMaxClause.class));
            SpanBooleanQueryRewriteWithMaxClause rewrite = (SpanBooleanQueryRewriteWithMaxClause) wrapper.getRewriteMethod();
            assertThat(rewrite.getMaxExpansions(), equalTo(IndexSearcher.getMaxClauseCount()));
            assertTrue(rewrite.isHardLimit());
        }
    }

    public void testFromJson() throws IOException {
        String json = "{\n"
            + "  \"span_multi\" : {\n"
            + "    \"match\" : {\n"
            + "      \"prefix\" : {\n"
            + "        \"user\" : {\n"
            + "          \"value\" : \"ki\",\n"
            + "          \"boost\" : 1.08\n"
            + "        }\n"
            + "      }\n"
            + "    },\n"
            + "    \"boost\" : 1.0\n"
            + "  }\n"
            + "}";

        SpanMultiTermQueryBuilder parsed = (SpanMultiTermQueryBuilder) parseQuery(json);
        checkGeneratedJson(json, parsed);

        assertEquals(json, "ki", ((PrefixQueryBuilder) parsed.innerQuery()).value());
        assertEquals(json, 1.08, parsed.innerQuery().boost(), 0.0001);
    }

    public void testDefaultMaxRewriteBuilder() throws Exception {
        Query query = QueryBuilders.spanMultiTermQueryBuilder(QueryBuilders.prefixQuery("body", "b")).toQuery(createShardContext());

        assertTrue(query instanceof SpanMultiTermQueryWrapper);
        if (query instanceof SpanMultiTermQueryWrapper) {
            MultiTermQuery.RewriteMethod rewriteMethod = ((SpanMultiTermQueryWrapper) query).getRewriteMethod();
            assertTrue(rewriteMethod instanceof SpanBooleanQueryRewriteWithMaxClause);
        }
    }

    public void testTermExpansionExceptionOnSpanFailure() throws Exception {
        try (Directory directory = newDirectory()) {
            try (RandomIndexWriter iw = new RandomIndexWriter(random(), directory, new WhitespaceAnalyzer())) {
                for (int i = 0; i < 3; i++) {
                    iw.addDocument(singleton(new TextField("body", "foo bar" + Integer.toString(i), Field.Store.NO)));
                }
                try (IndexReader reader = iw.getReader()) {
                    int origBoolMaxClauseCount = IndexSearcher.getMaxClauseCount();
                    IndexSearcher.setMaxClauseCount(1);
                    try {
                        QueryBuilder queryBuilder = new SpanMultiTermQueryBuilder(QueryBuilders.prefixQuery("body", "bar"));
                        IndexSearcher searcher = new IndexSearcher(reader);
                        Query query = queryBuilder.toQuery(createShardContext(searcher));
                        RuntimeException exc = expectThrows(RuntimeException.class, () -> query.rewrite(searcher));
                        assertThat(exc.getMessage(), containsString("maxClauseCount"));
                    } finally {
                        IndexSearcher.setMaxClauseCount(origBoolMaxClauseCount);
                    }
                }
            }
        }
    }

    public void testTopNMultiTermsRewriteInsideSpan() throws Exception {
        Query query = QueryBuilders.spanMultiTermQueryBuilder(QueryBuilders.prefixQuery("body", "b").rewrite("top_terms_boost_2000"))
            .toQuery(createShardContext());

        assertTrue(query instanceof SpanMultiTermQueryWrapper);
        if (query instanceof SpanMultiTermQueryWrapper) {
            MultiTermQuery.RewriteMethod rewriteMethod = ((SpanMultiTermQueryWrapper) query).getRewriteMethod();
            assertFalse(rewriteMethod instanceof SpanBooleanQueryRewriteWithMaxClause);
        }

    }

    public void testVisit() {
        MultiTermQueryBuilder multiTermQueryBuilder = new PrefixQueryBuilderTests().createTestQueryBuilder();
        SpanMultiTermQueryBuilder spanMultiTermQueryBuilder = new SpanMultiTermQueryBuilder(multiTermQueryBuilder);
        List<QueryBuilder> visitorQueries = new ArrayList<>();
        spanMultiTermQueryBuilder.visit(createTestVisitor(visitorQueries));
        assertEquals(2, visitorQueries.size());
    }
}
