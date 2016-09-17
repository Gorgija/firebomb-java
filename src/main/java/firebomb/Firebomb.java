package firebomb;

import firebomb.database.DatabaseManager;
import firebomb.definition.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Firebomb {
    private static Firebomb ourInstance;

    public static Firebomb getInstance() {
        if (ourInstance == null) {
            throw new IllegalStateException("Firebomb instance not initialized.");
        }

        return ourInstance;
    }

    private DatabaseManager connection;
    private String rootPath = ""; // Default Firebase root

    public static void initialize(DatabaseManager connection) {
        ourInstance = new Firebomb(connection);
    }

    public static void initialize(DatabaseManager connection, String rootPath) {
        initialize(connection);
        ourInstance.setRootPath(rootPath);
    }

    public Firebomb(DatabaseManager connection) {
        this.connection = connection;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public <T> CompletableFuture<T> find(Class<T> entityType, String id) {
        CompletableFuture<T> promise = new CompletableFuture<T>();

        EntityDefinition entityDef;
        try {
            entityDef = new EntityDefinition(entityType);
        } catch (Exception e) {
            promise.completeExceptionally(e);
            return promise;
        }

        String path = path(entityDef.getReference(), id);
        connection.read(path(rootPath, path))
                .thenAccept(entityData -> {
                    try {
                        if (entityData.getValue() == null) {
                            promise.complete(null);
                            return;
                        }

                        T entity = entityType.newInstance();

                        // Set ID
                        entityDef.setId(entity, (String) entityData.child(entityDef.getIdName()).getValue());

                        // Set fields
                        for (FieldDefinition fieldDef : entityDef.getFieldDefinitions()) {
                            fieldDef.set(entity, entityData.child(fieldDef.getName()).getValue(fieldDef.getType()));
                            // TODO Verify lists
                        }

                        // Set relations
                        // TODO Implement eager loading
                        for (ManyToManyDefinition manyToManyDef : entityDef.getManyToManyDefinitions()) {
                            List<Object> foreignEntities = new ArrayList<>();
                            entityData.child(manyToManyDef.getName()).getChildren()
                                    .forEach(foreignEntityData -> foreignEntities.add(
                                            foreignEntityData.getValue(manyToManyDef.getForeignEntityType())));
                            manyToManyDef.set(entity, foreignEntities);
                        }

                        for (ManyToOneDefinition manyToOneDef : entityDef.getManyToOneDefinitions()) {
                            manyToOneDef.set(entity, entityData.child(manyToOneDef.getName()).getChildren().get(0)
                                    .getValue(manyToOneDef.getForeignEntityType()));
                        }

                        for (OneToManyDefinition oneToManyDef : entityDef.getOneToManyDefinitions()) {
                            List<Object> foreignEntities = new ArrayList<>();
                            entityData.child(oneToManyDef.getName()).getChildren()
                                    .forEach(foreignEntityData -> foreignEntities.add(
                                            foreignEntityData.getValue(oneToManyDef.getForeignEntityType())));
                            oneToManyDef.set(entity, foreignEntities);
                        }

                        promise.complete(entity);
                    } catch (InstantiationException | IllegalAccessException e) {
                        promise.completeExceptionally(e);
                    }
                })
                .exceptionally(throwable -> {
                    promise.completeExceptionally(throwable);
                    return null;
                });

        return promise;
    }

    public <T> CompletableFuture<T> persist(T entity) {
        CompletableFuture<T> promise = new CompletableFuture<>();

        // Construct entity definition
        EntityDefinition entityDef;
        try {
            entityDef = new EntityDefinition(entity.getClass());
        } catch (DefinitionException e) {
            promise.completeExceptionally(e);
            return promise;
        }

        Map<String, Object> writeMap = new HashMap<>();

        // Get Id if necessary
        String idName = entityDef.getIdName();
        String entityId = entityDef.getId(entity);
        if (entityId == null && !entityDef.getIdDefinition().isGeneratedValue()) {
            promise.completeExceptionally(new FirebombException(
                    "Id '" + entityDef.getName() + "." + idName + "' cannot be null."));
            return promise;
        } else if (entityId == null) {
            entityId = connection.generateId(entityDef.getReference());
            entityDef.setId(entity, entityId);
        } else {
            // Cleanup currently persisted foreign indexes
            try {
                writeMap.putAll(constructDeleteMap(entityDef, entityId));
            } catch (InterruptedException | ExecutionException e) {
                promise.completeExceptionally(e);
                return promise;
            }
        }

        // Add Id
        String entityPath = path(entityDef.getReference(), entityId);
        writeMap.put(path(entityPath, entityDef.getIdName()), entityId);

        // Add fields
        for (FieldDefinition fieldDef : entityDef.getFieldDefinitions()) {
            Object fieldValue = fieldDef.get(entity);
            if (fieldDef.isNonNull() && fieldValue == null) {
                promise.completeExceptionally(new FirebombException(
                        "Non-null field '" + entityDef.getName() + "." + fieldDef.getName() + "' cannot be null."));
                return promise;
            }
            writeMap.put(path(entityPath, fieldDef.getName()), fieldValue);
        }

        // Add ManyToMany
        for (ManyToManyDefinition manyToManyDef : entityDef.getManyToManyDefinitions()) {
            String foreignIdName = manyToManyDef.getForeignIdName();
            for (Object foreignEntity : manyToManyDef.get(entity)) {
                String foreignId = manyToManyDef.getForeignId(foreignEntity);
                writeMap.put(path(entityPath, manyToManyDef.getName(), foreignId), kvp(foreignIdName, foreignId));
                writeMap.put(path(manyToManyDef.constructForeignIndexPath(foreignId), entityId), kvp(idName, entityId));
            }
        }

        // Add ManyToOne
        for (ManyToOneDefinition manyToOneDef : entityDef.getManyToOneDefinitions()) {
            Object foreignEntity = manyToOneDef.get(entity);
            String foreignIdName = manyToOneDef.getForeignIdName();
            String foreignId = manyToOneDef.getForeignId(foreignEntity);
            writeMap.put(path(entityPath, manyToOneDef.getName(), foreignId), kvp(foreignIdName, foreignId));
            writeMap.put(path(manyToOneDef.constructForeignIndexPath(foreignId), entityId), kvp(idName, entityId));
        }

        // Add OneToMany
        for (OneToManyDefinition oneToManyDef : entityDef.getOneToManyDefinitions()) {
            String foreignIdName = oneToManyDef.getForeignIdName();
            for (Object foreignEntity : oneToManyDef.get(entity)) {
                String foreignId = oneToManyDef.getForeignId(foreignEntity);
                writeMap.put(path(entityPath, oneToManyDef.getName(), foreignId), kvp(foreignIdName, foreignId));
                writeMap.put(oneToManyDef.constructForeignFieldPath(foreignId), entityId);
            }
        }

        // Write
        return connection.write(rootPath, writeMap).thenApply(aVoid -> entity);
    }

    public CompletableFuture<Void> remove(Class entityType, String id) {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        // Construct entity definition
        EntityDefinition entityDef;
        try {
            entityDef = new EntityDefinition(entityType);
        } catch (DefinitionException e) {
            promise.completeExceptionally(e);
            return promise;
        }

        Map<String, Object> writeMap = new HashMap<>();

        // Cleanup currently persisted foreign indexes
        try {
            writeMap.putAll(constructDeleteMap(entityDef, id));
        } catch (InterruptedException | ExecutionException e) {
            promise.completeExceptionally(e);
            return promise;
        }

        // TODO Delete ManyToOne foreign entities?
        System.out.println(writeMap);
        return connection.write(rootPath, writeMap);
    }

    private Map<String, Object> constructDeleteMap(EntityDefinition entityDefinition, String id)
            throws ExecutionException, InterruptedException {
        Map<String, Object> writeMap = new HashMap<>();

        Object entity = find(entityDefinition.getEntityType(), id).get();
        if (entity == null) {
            return writeMap;
        }

        String entityPath = path(entityDefinition.getReference(), id);

        // Add Id
        writeMap.put(path(entityPath, entityDefinition.getIdName()), null);

        // Add fields
        for (FieldDefinition fieldDef : entityDefinition.getFieldDefinitions()) {
            writeMap.put(path(entityPath, fieldDef.getName()), null);
        }

        // Add ManyToMany
        for (ManyToManyDefinition manyToManyDef : entityDefinition.getManyToManyDefinitions()) {
            for (Object foreignEntity : manyToManyDef.get(entity)) {
                String foreignId = manyToManyDef.getForeignId(foreignEntity);
                writeMap.put(path(entityPath, manyToManyDef.getName(), foreignId), null);
                writeMap.put(path(manyToManyDef.constructForeignIndexPath(foreignId), id), null);
            }
        }

        // Add ManyToOne
        for (ManyToOneDefinition manyToOneDef : entityDefinition.getManyToOneDefinitions()) {
            Object foreignEntity = manyToOneDef.get(entity);
            String foreignId = manyToOneDef.getForeignId(foreignEntity);
            writeMap.put(path(entityPath, manyToOneDef.getName(), foreignId), null);
            writeMap.put(path(manyToOneDef.constructForeignIndexPath(foreignId), id), null);
        }

        // Add OneToMany
        for (OneToManyDefinition oneToManyDef : entityDefinition.getOneToManyDefinitions()) {
            for (Object foreignEntity : oneToManyDef.get(entity)) {
                String foreignId = oneToManyDef.getForeignId(foreignEntity);
                writeMap.put(path(entityPath, oneToManyDef.getName(), foreignId), null);
                writeMap.put(oneToManyDef.constructForeignFieldPath(foreignId), null);
            }
        }

        return writeMap;
    }

    private static String path(String... nodes) {
        return String.join("/", (CharSequence[]) nodes);
    }

    private static Map<String, String> kvp(String key, String value) {
        Map<String, String> kvp = new HashMap<>();
        kvp.put(key, value);
        return kvp;
    }
}
