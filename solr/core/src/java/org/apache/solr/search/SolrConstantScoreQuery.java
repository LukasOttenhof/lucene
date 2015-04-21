package org.apache.solr.search;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.solr.common.SolrException;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A query that wraps a filter and simply returns a constant score equal to the
 * query boost for every document in the filter.   This Solr extension also supports
 * weighting of a SolrFilter.
 *
 * Experimental and subject to change.
 */
public class SolrConstantScoreQuery extends ConstantScoreQuery implements ExtendedQuery {
  private final Filter filter;
  boolean cache = true;  // cache by default
  int cost;

  public SolrConstantScoreQuery(Filter filter) {
    super(filter);
    this.filter = filter;
  }

  /** Returns the encapsulated filter */
  public Filter getFilter() {
    return filter;
  }

  @Override
  public void setCache(boolean cache) {
    this.cache = cache;
  }

  @Override
  public boolean getCache() {
    return cache;
  }

  @Override
  public void setCacheSep(boolean cacheSep) {
  }

  @Override
  public boolean getCacheSep() {
    return false;
  }

  @Override
  public void setCost(int cost) {
    this.cost = cost;
  }

  @Override
  public int getCost() {
    return cost;
  }


  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    return this;
  }

  protected class ConstantWeight extends Weight {
    private float queryNorm;
    private float queryWeight;
    private Map context;

    public ConstantWeight(IndexSearcher searcher) throws IOException {
      super(SolrConstantScoreQuery.this);
      this.context = ValueSource.newContext(searcher);
      if (filter instanceof SolrFilter)
        ((SolrFilter)filter).createWeight(context, searcher);
    }

    @Override
    public void extractTerms(Set<org.apache.lucene.index.Term> terms) {
      // OK to not add any terms when used for MultiSearcher,
      // but may not be OK for highlighting
    }

    @Override
    public float getValueForNormalization() throws IOException {
      queryWeight = getBoost();
      return queryWeight * queryWeight;
    }

    @Override
    public void normalize(float norm, float topLevelBoost) {
      this.queryNorm = norm * topLevelBoost;
      queryWeight *= this.queryNorm;
    }

    @Override
    public Scorer scorer(LeafReaderContext context, Bits acceptDocs) throws IOException {
      return new ConstantScorer(context, this, queryWeight, acceptDocs);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {

      ConstantScorer cs = new ConstantScorer(context, this, queryWeight, context.reader().getLiveDocs());
      boolean exists = cs.docIdSetIterator.advance(doc) == doc;

      if (exists) {
        return Explanation.match(queryWeight, "ConstantScoreQuery(" + filter + "), product of:",
            Explanation.match(getBoost(), "boost"),
            Explanation.match(queryNorm,"queryNorm"));
      } else {
        return Explanation.noMatch("ConstantScoreQuery(" + filter + ") doesn't match id " + doc);
      }
    }
  }

  protected class ConstantScorer extends Scorer {
    final DocIdSetIterator docIdSetIterator;
    final float theScore;
    final Bits acceptDocs;
    int doc = -1;

    public ConstantScorer(LeafReaderContext context, ConstantWeight w, float theScore, Bits acceptDocs) throws IOException {
      super(w);
      this.theScore = theScore;
      this.acceptDocs = acceptDocs;
      DocIdSet docIdSet = filter instanceof SolrFilter ? ((SolrFilter)filter).getDocIdSet(w.context, context, acceptDocs) : filter.getDocIdSet(context, acceptDocs);
      if (docIdSet == null) {
        docIdSetIterator = DocIdSetIterator.empty();
      } else {
        DocIdSetIterator iter = docIdSet.iterator();
        if (iter == null) {
          docIdSetIterator = DocIdSetIterator.empty();
        } else {
          docIdSetIterator = iter;
        }
      }
    }

    @Override
    public int nextDoc() throws IOException {
      return docIdSetIterator.nextDoc();
    }

    @Override
    public int docID() {
      return docIdSetIterator.docID();
    }

    @Override
    public float score() throws IOException {
      return theScore;
    }
    
    @Override
    public int freq() throws IOException {
      return 1;
    }

    @Override
    public int advance(int target) throws IOException {
      return docIdSetIterator.advance(target);
    }

    @Override
    public long cost() {
      return docIdSetIterator.cost();
    }
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) {
    try {
      return new SolrConstantScoreQuery.ConstantWeight(searcher);
    } catch (IOException e) {
      // TODO: remove this if ConstantScoreQuery.createWeight adds IOException
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /** Prints a user-readable version of this query. */
  @Override
  public String toString(String field) {
    return ExtendedQueryBase.getOptionsString(this) + "ConstantScore(" + filter.toString()
      + (getBoost()==1.0 ? ")" : "^" + getBoost());
  }

  /** Returns true if <code>o</code> is equal to this. */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SolrConstantScoreQuery)) return false;
    SolrConstantScoreQuery other = (SolrConstantScoreQuery)o;
    return this.getBoost()==other.getBoost() && filter.equals(other.filter);
  }

  /** Returns a hash code value for this object. */
  @Override
  public int hashCode() {
    // Simple add is OK since no existing filter hashcode has a float component.
    return filter.hashCode() + Float.floatToIntBits(getBoost());
  }

}
