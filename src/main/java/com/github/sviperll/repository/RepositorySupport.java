/*
 * Copyright (C) 2013 Victor Nazarov <asviraspossible@gmail.com>
 */

package com.github.sviperll.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.github.sviperll.OptionalVisitor;
import com.github.sviperll.OptionalVisitors;

public class RepositorySupport implements SQLTransactionManager {
    private static <T, U> boolean isElementsEquals(AtomicStorableClassComponent<T, U> definition, T value1, T value2) {
        U elementValue1 = definition.getComponent(value1);
        U elementValue2 = definition.getComponent(value2);
        if (elementValue1 == elementValue2)
            return true;
        else if (elementValue1 == null || elementValue2 == null)
            return false;
        else
            return elementValue1.equals(elementValue2);
    }

    private final SQLConnection connection;

    public RepositorySupport(SQLConnection connection) {
        this.connection = connection;
    }

    public <K, V> K putNewEntry(AutogeneratedKeyIndexedRepositoryConfiguration<K, V> configuration, V attributes) throws SQLException {
        SQLBuilder sqlBuilder = new SQLBuilder();
        sqlBuilder.append("INSERT INTO ").append(configuration.getTableName()).append(" (");
        List<? extends AtomicStorableClassComponent<V, ?>> elements = configuration.getValueDefinition().getAtomicComponents();
        sqlBuilder.appendJoinedTupleElements(", ", "{0}", elements);
        sqlBuilder.append(") VALUES (");
        sqlBuilder.appendJoinedTupleElements(", ", "?", elements);
        sqlBuilder.append(")");
        String sql = sqlBuilder.toString();
        PreparedStatement statement = connection.prepareStatementWithAutogeneratedKeys(sql);
        try {
            StatementSetter statementSetter = new StatementSetter(statement);
            for (AtomicStorableClassComponent<V, ?> element: elements)
                statementSetter.setElement(element, attributes);
            int insertedRowsCount = statement.executeUpdate();
            if (insertedRowsCount != 1)
                throw new IllegalStateException("Unable to insert into " + configuration.getTableName());
            ResultSet resultSet = statement.getGeneratedKeys();
            try {
                boolean hasNextRecord = resultSet.next();
                if (!hasNextRecord)
                    throw new IllegalStateException("No autogenerated key for " + configuration.getTableName());
                return configuration.getAutogeneratedKeyDefinition().createInstance(resultSet);
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    public <K, V, R, E extends Exception> R get(IndexedRepositoryConfiguration<K, V> configuration, K key, OptionalVisitor<V, R, E> optionalVisitor)
            throws E, SQLException {
        SQLBuilder sqlBuilder = new SQLBuilder();
        sqlBuilder.append("SELECT * FROM ").append(configuration.getTableName());
        List<? extends AtomicStorableClassComponent<K, ?>> keyElements = configuration.getKeyDefinition().getAtomicComponents();
        if (!keyElements.isEmpty()) {
            sqlBuilder.append(" WHERE ");
            sqlBuilder.appendJoinedTupleElements(" AND ", "{0} = ?", keyElements);
        }
        String sql = sqlBuilder.toString();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            StatementSetter statementSetter = new StatementSetter(statement);
            for (AtomicStorableClassComponent<K, ?> element: keyElements)
                statementSetter.setElement(element, key);
            ResultSet resultSet = statement.executeQuery();
            try {
                boolean hasNextRecord = resultSet.next();
                if (!hasNextRecord)
                    return optionalVisitor.missing();
                else {
                    V value = configuration.getValueDefinition().createInstance(resultSet);
                    return optionalVisitor.present(value);
                }
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    public <V, O> List<V> entryList(ReadableRepositoryConfiguration<V, O> configuration, SlicingQuery<O> slicing) throws SQLException {
        return entryList(new VoidReadableRepositoryDirectoryConfiguration<V, O>(configuration), null, slicing);
    }

    public <K, V, O> List<V> entryList(ReadableRepositoryDirectoryConfiguration<K, V, O> configuration, K key, SlicingQuery<O> slicing) throws SQLException {
        List<? extends AtomicStorableClassComponent<O, ?>> orderingElements = configuration.getOrderingDefinition().getAtomicComponents();
        List<? extends AtomicStorableClassComponent<K, ?>> keyElements = configuration.getKeyDefinition().getAtomicComponents();
        SQLBuilder sqlBuilder = new SQLBuilder();
        sqlBuilder.append("SELECT * FROM ");
        sqlBuilder.append(configuration.getTableName());
        if (slicing.hasConditions() || !keyElements.isEmpty())
            sqlBuilder.append(" WHERE ");
        if (!keyElements.isEmpty()) {
            sqlBuilder.appendJoinedTupleElements(" AND ", "{0} = ?", keyElements);
            if (slicing.hasConditions())
                sqlBuilder.append(" AND ");
        }
        if (slicing.hasConditions()) {
            if (orderingElements.isEmpty())
                throw new IllegalArgumentException("Ordering definition shouldn't be empty!");
            else {
                if (orderingElements.size() == 1)
                    sqlBuilder.appendConditionForColumn(slicing.condition(), orderingElements.get(0));
                else {
                    sqlBuilder.append("(");
                    sqlBuilder.appendConditionForColumn(slicing.condition(), orderingElements.get(0));
                    for (int i = 1; i < orderingElements.size(); i++) {
                        sqlBuilder.append("OR (");
                        sqlBuilder.appendJoinedTupleElements(" AND ", "{0} = ?", orderingElements.subList(0, i));
                        sqlBuilder.append(" AND ");
                        sqlBuilder.appendConditionForColumn(slicing.condition(), orderingElements.get(i));
                        sqlBuilder.append(")");
                    }
                    sqlBuilder.append(")");
                }
            }
        }
        if (slicing.isOrdered()) {
            sqlBuilder.append(" ORDER BY ");
            if (orderingElements.isEmpty())
                throw new IllegalArgumentException("Ordering definition shouldn't be empty!");
            else {
                String format = slicing.isDescending() ? "{0} DESC" : "{0} ASC";
                sqlBuilder.appendJoinedTupleElements(", ", format, orderingElements);
            }
        }
        if (slicing.hasLimit())
            sqlBuilder.append(" LIMIT ?");
        String sql = sqlBuilder.toString();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                StatementSetter statementSetter = new StatementSetter(statement);
                for (AtomicStorableClassComponent<K, ?> element: keyElements)
                    statementSetter.setElement(element, key);
                if (slicing.hasConditions()) {
                    for (int i = 0; i < orderingElements.size(); i++) {
                        for (int j = 0; j < i; j++)
                            statementSetter.setElement(orderingElements.get(j), slicing.condition().value());
                        statementSetter.setElement(orderingElements.get(i), slicing.condition().value());
                    }
                }
                if (slicing.hasLimit())
                    statementSetter.setInt(slicing.limit());
                ResultSet resultSet = statement.executeQuery();
                try {
                    List<V> result;
                    if (slicing.hasLimit())
                        result = new ArrayList<V>(slicing.limit());
                    else
                        result = new ArrayList<V>();
                    while (resultSet.next()) {
                        V entry = configuration.getEntryDefinition().createInstance(resultSet);
                        result.add(entry);
                    }
                    if (slicing.postProcessing().needsToBeReveresed())
                        Collections.reverse(result);
                    return result;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException ex) {
            throw new SQLException("Error executing query: " + sql, ex);
        }
    }

    public <K> boolean remove(RepositoryIndex<K> configuration, K key) throws SQLException {
        SQLBuilder sqlBuilder = new SQLBuilder();
        sqlBuilder.append("DELETE FROM ").append(configuration.getTableName());
        List<? extends AtomicStorableClassComponent<K, ?>> keyElements = configuration.getKeyDefinition().getAtomicComponents();
        if (!keyElements.isEmpty()) {
            sqlBuilder.append(" WHERE ");
            sqlBuilder.appendJoinedTupleElements(" AND ", "{0} = ?", keyElements);
        }
        String sql = sqlBuilder.toString();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            StatementSetter statementSetter = new StatementSetter(statement);
            for (AtomicStorableClassComponent<K, ?> element: configuration.getKeyDefinition().getAtomicComponents())
                statementSetter.setElement(element, key);
            int removedRecordsCount = statement.executeUpdate();
            if (removedRecordsCount > 1)
                throw new IllegalStateException(
                        "Removed several records from " + configuration.getTableName() + " table with single key value: " + key);
            return removedRecordsCount == 1;
        } finally {
            statement.close();
        }
    }

    public <K, V> boolean putIfExists(IndexedRepositoryConfiguration<K, V> configuration, K key, Changed<V> attributes) throws SQLException {
        ArrayList<String> assignments = new ArrayList<String>();
        List<? extends AtomicStorableClassComponent<V, ?>> valueElements = configuration.getValueDefinition().getAtomicComponents();
        for (AtomicStorableClassComponent<V, ?> element: valueElements) {
            if (!isElementsEquals(element, attributes.oldValue(), attributes.newValue())) {
                assignments.add(element.getColumn().getColumnName() + " = ?");
            }
        }
        if (assignments.isEmpty()) {
            return false;
        } else {
            SQLBuilder sqlBuilder = new SQLBuilder();
            sqlBuilder.append("UPDATE ").append(configuration.getTableName()).append(" SET ");
            sqlBuilder.appendJoined(", ", assignments);

            List<? extends AtomicStorableClassComponent<K, ?>> keyElements = configuration.getKeyDefinition().getAtomicComponents();
            if (!keyElements.isEmpty()) {
                sqlBuilder.append(" WHERE ");
                sqlBuilder.appendJoinedTupleElements(" AND ", "{0} = ?", keyElements);
            }
            PreparedStatement statement = connection.prepareStatement(sqlBuilder.toString());
            try {
                StatementSetter statementSetter = new StatementSetter(statement);
                for (AtomicStorableClassComponent<V, ?> element: valueElements) {
                    if (!isElementsEquals(element, attributes.oldValue(), attributes.newValue())) {
                        statementSetter.setElement(element, attributes.newValue());
                    }
                }
                for (AtomicStorableClassComponent<K, ?> element: keyElements) {
                    statementSetter.setElement(element, key);
                }

                int removedRecordsCount = statement.executeUpdate();
                if (removedRecordsCount > 1)
                    throw new IllegalStateException(
                            "Updated several " + configuration.getTableName() + " with single id value: " + key);
                return removedRecordsCount == 1;
            } finally {
                statement.close();
            }
        }
    }

    public <K, V> void putNewEntry(IndexedRepositoryConfiguration<K, V> configuration, K key, V attributes) throws SQLException {
        SQLBuilder sqlBuilder = new SQLBuilder();
        sqlBuilder.append("INSERT INTO ").append(configuration.getTableName()).append(" (");
        List<? extends AtomicStorableClassComponent<K, ?>> keyElements = configuration.getKeyDefinition().getAtomicComponents();
        List<? extends AtomicStorableClassComponent<V, ?>> valueElements = configuration.getValueDefinition().getAtomicComponents();
        sqlBuilder.appendJoinedTupleElements(", ", "{0}", keyElements);
        sqlBuilder.appendJoinedTupleElements(", ", "{0}", valueElements);
        sqlBuilder.append(") VALUES (");
        sqlBuilder.appendJoinedTupleElements(", ", "?", keyElements);
        sqlBuilder.appendJoinedTupleElements(", ", "?", valueElements);
        sqlBuilder.append(")");
        String sql = sqlBuilder.toString();
        PreparedStatement statement = connection.prepareStatementWithoutAutogeneratedKeys(sql);
        try {
            StatementSetter statementSetter = new StatementSetter(statement);
            for (AtomicStorableClassComponent<K, ?> element: keyElements)
                statementSetter.setElement(element, key);
            for (AtomicStorableClassComponent<V, ?> element: valueElements)
                statementSetter.setElement(element, attributes);
            int insertedRowsCount = statement.executeUpdate();
            if (insertedRowsCount != 1)
                throw new IllegalStateException("Unable to insert into " + configuration.getTableName());
        } finally {
            statement.close();
        }
    }

    public <K, V> boolean put(IndexedRepositoryConfiguration<K, V> configuration, K key, V attributes) throws SQLException {
        V oldAttributes = get(configuration, key, OptionalVisitors.<V>returnNull());
        if (oldAttributes != null)
            return putIfExists(configuration, key, Changed.fromTo(oldAttributes, attributes));
        else {
            putNewEntry(configuration, key, attributes);
            return true;
        }
    }

    @Override
    public void beginTransaction() throws SQLException {
        connection.beginTransaction();
    }

    @Override
    public void beginTransaction(SQLTransactionIsolationLevel level) throws SQLException {
        connection.beginTransaction(level);
    }

    @Override
    public void commitTransaction() throws SQLException {
        connection.commitTransaction();
    }

    @Override
    public void rollbackTransaction() throws SQLException {
        connection.rollbackTransaction();
    }

    @Override
    public void rollbackTransactionIfNotCommited() throws SQLException {
        connection.rollbackTransactionIfNotCommited();
    }

    public SQLConnection connection() {
        return connection;
    }

    private static class StatementSetter {
        private final PreparedStatement statement;
        private int index;
        public StatementSetter(PreparedStatement statement, int index) {
            this.statement = statement;
            this.index = index;
        }

        private StatementSetter(PreparedStatement statement) {
            this(statement, 1);
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public <T, U> void setElement(AtomicStorableClassComponent<T, U> definition, T value) throws SQLException {
            U columnValue = definition.getComponent(value);
            definition.getColumn().createStatementSetter(statement).setValue(index, columnValue);
            index++;
        }

        private void setInt(int value) throws SQLException {
            statement.setInt(index, value);
            index++;
        }
    }

    private static class VoidReadableRepositoryDirectoryConfiguration<V, O> implements ReadableRepositoryDirectoryConfiguration<Void, V, O> {
        private static final StorableClass<Void> VOID_CLASS = new VoidStorableClass();
        final ReadableRepositoryConfiguration<V, O> reader;
        public VoidReadableRepositoryDirectoryConfiguration(ReadableRepositoryConfiguration<V, O> reader) {
            this.reader = reader;
        }

        @Override
        public StorableClass<V> getEntryDefinition() {
            return reader.getEntryDefinition();
        }

        @Override
        public StorableClass<O> getOrderingDefinition() {
            return reader.getOrderingDefinition();
        }

        @Override
        public String getTableName() {
            return reader.getTableName();
        }

        @Override
        public StorableClass<Void> getKeyDefinition() {
            return VOID_CLASS;
        }
    }

    private static class VoidStorableClass implements StorableClass<Void> {
        @Override
        public List<AtomicStorableClassComponent<Void, ?>> getAtomicComponents() {
            return Collections.emptyList();
        }

        @Override
        public Void createInstance(ResultSet resultSet) {
            return null;
        }
    }
}
