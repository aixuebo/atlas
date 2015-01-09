package org.apache.hadoop.metadata.services;

import com.google.common.collect.ImmutableList;
import com.thinkaurelius.titan.core.TitanGraph;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import org.apache.hadoop.metadata.ITypedReferenceableInstance;
import org.apache.hadoop.metadata.MetadataException;
import org.apache.hadoop.metadata.MetadataService;
import org.apache.hadoop.metadata.Referenceable;
import org.apache.hadoop.metadata.service.Services;
import org.apache.hadoop.metadata.storage.memory.MemRepository;
import org.apache.hadoop.metadata.types.AttributeDefinition;
import org.apache.hadoop.metadata.types.ClassType;
import org.apache.hadoop.metadata.types.DataTypes;
import org.apache.hadoop.metadata.types.HierarchicalType;
import org.apache.hadoop.metadata.types.HierarchicalTypeDefinition;
import org.apache.hadoop.metadata.types.IDataType;
import org.apache.hadoop.metadata.types.Multiplicity;
import org.apache.hadoop.metadata.types.StructTypeDefinition;
import org.apache.hadoop.metadata.types.TraitType;
import org.apache.hadoop.metadata.types.TypeSystem;
import org.apache.hadoop.metadata.util.GraphUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class GraphBackedMetadataRepositoryServiceTest {

    private static final String ENTITY_TYPE = "hive-table";

    private TitanGraphService titanGraphService;
    private GraphBackedMetadataRepositoryService repositoryService;
    protected org.apache.hadoop.metadata.MetadataService ms;
    private String guid;

    @BeforeClass
    public void setUp() throws Exception {
        titanGraphService = new TitanGraphService();
        titanGraphService.start();
        Services.get().register(titanGraphService);

        DefaultTypesService typesService = new DefaultTypesService();
        typesService.start();
        Services.get().register(typesService);
        TypeSystem ts = typesService.getTypeSystem();

        repositoryService = new GraphBackedMetadataRepositoryService();
        repositoryService.start();
        Services.get().register(repositoryService);

        // todo - only used for types
        MemRepository mr = new MemRepository(ts);
        ms = new org.apache.hadoop.metadata.MetadataService(mr, ts);
        MetadataService.setCurrentService(ms);

        defineDeptEmployeeTypes(ts);
    }

    @AfterClass
    public void tearDown() throws Exception {
        Services.get().getService(GraphBackedMetadataRepositoryService.NAME).close();
        Services.get().getService(TitanGraphService.NAME).close();
        Services.get().reset();
    }

    @Test
    public void testGetName() throws Exception {
        Assert.assertEquals(GraphBackedMetadataRepositoryService.NAME,
                GraphBackedMetadataRepositoryService.class.getSimpleName());
        Assert.assertEquals(repositoryService.getName(), GraphBackedMetadataRepositoryService.NAME);
    }

    @Test
    public void testSubmitEntity() throws Exception {
        TypeSystem typeSystem = MetadataService.getCurrentService().getTypeSystem();
        Referenceable hrDept = createDeptEg1(typeSystem);
        ClassType deptType = typeSystem.getDataType(ClassType.class, "Department");
        ITypedReferenceableInstance hrDept2 = deptType.convert(hrDept, Multiplicity.REQUIRED);

        guid = repositoryService.createEntity(hrDept2, ENTITY_TYPE);
        Assert.assertNotNull(guid);

        dumpGraph();
    }

    private void dumpGraph() {
        TitanGraph graph = titanGraphService.getTitanGraph();
        for (Vertex v : graph.getVertices()) {
            // System.out.println("****v = " + GraphUtils.vertexString(v));
            System.out.println("v = " + v);
            for (Edge e : v.getEdges(Direction.OUT)) {
                System.out.println("****e = " + GraphUtils.edgeString(e));
            }
        }
    }

    @Test(dependsOnMethods = "testSubmitEntity")
    public void testGetEntityDefinition() throws Exception {
        ITypedReferenceableInstance entity = repositoryService.getEntityDefinition(guid);
        Assert.assertNotNull(entity);
    }

    @Test
    public void testGetEntityDefinitionNonExistent() throws Exception {
        ITypedReferenceableInstance entity = repositoryService.getEntityDefinition("blah");
        Assert.assertNull(entity);
    }

    @Test
    public void testGetEntityList() throws Exception {
        List<String> entityList = repositoryService.getEntityList(ENTITY_TYPE);
        Assert.assertNotNull(entityList);
        Assert.assertEquals(entityList.size(), 0); // as this is not implemented yet
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void testStartWithOutGraphServiceRegistration() throws Exception {
        try {
            Services.get().reset();
            GraphBackedMetadataRepositoryService repositoryService = new
                    GraphBackedMetadataRepositoryService();
            repositoryService.start();
            Assert.fail("This should have thrown an exception");
        } finally {
            Services.get().register(titanGraphService);
            Services.get().register(repositoryService);
        }
    }

    /*
     * Class Hierarchy is:
     *   Department(name : String, employees : Array[Person])
     *   Person(name : String, department : Department, manager : Manager)
     *   Manager(subordinates : Array[Person]) extends Person
     *
     * Persons can have SecurityClearance(level : Int) clearance.
     */
    protected void defineDeptEmployeeTypes(TypeSystem ts) throws MetadataException {

        HierarchicalTypeDefinition<ClassType> deptTypeDef =
                createClassTypeDef("Department", ImmutableList.<String>of(),
                        createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        new AttributeDefinition("employees",
                                String.format("array<%s>", "Person"), Multiplicity.COLLECTION, true,
                                "department")
                );

        HierarchicalTypeDefinition<ClassType> personTypeDef = createClassTypeDef("Person",
                ImmutableList.<String>of(),
                createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                new AttributeDefinition("department",
                        "Department", Multiplicity.REQUIRED, false, "employees"),
                new AttributeDefinition("manager",
                        "Manager", Multiplicity.OPTIONAL, false, "subordinates")
        );

        HierarchicalTypeDefinition<ClassType> managerTypeDef = createClassTypeDef("Manager",
                ImmutableList.<String>of("Person"),
                new AttributeDefinition("subordinates",
                        String.format("array<%s>", "Person"), Multiplicity.COLLECTION, false,
                        "manager")
        );

        HierarchicalTypeDefinition<TraitType> securityClearanceTypeDef = createTraitTypeDef(
                "SecurityClearance",
                ImmutableList.<String>of(),
                createRequiredAttrDef("level", DataTypes.INT_TYPE)
        );

        ts.defineTypes(ImmutableList.<StructTypeDefinition>of(),
                ImmutableList.<HierarchicalTypeDefinition<TraitType>>of(securityClearanceTypeDef),
                ImmutableList.<HierarchicalTypeDefinition<ClassType>>of(deptTypeDef, personTypeDef,
                        managerTypeDef));

        ImmutableList<HierarchicalType> types = ImmutableList.of(
                ts.getDataType(HierarchicalType.class, "SecurityClearance"),
                ts.getDataType(ClassType.class, "Department"),
                ts.getDataType(ClassType.class, "Person"),
                ts.getDataType(ClassType.class, "Manager")
        );

        ms.getRepository().defineTypes(types);
    }

    protected Referenceable createDeptEg1(TypeSystem ts) throws MetadataException {
        Referenceable hrDept = new Referenceable("Department");
        Referenceable john = new Referenceable("Person");
        Referenceable jane = new Referenceable("Manager", "SecurityClearance");

        hrDept.set("name", "hr");
        john.set("name", "John");
        john.set("department", hrDept);
        jane.set("name", "Jane");
        jane.set("department", hrDept);

        john.set("manager", jane);

        hrDept.set("employees", ImmutableList.<Referenceable>of(john, jane));

        jane.set("subordinates", ImmutableList.<Referenceable>of(john));

        jane.getTrait("SecurityClearance").set("level", 1);

        ClassType deptType = ts.getDataType(ClassType.class, "Department");
        ITypedReferenceableInstance hrDept2 = deptType.convert(hrDept, Multiplicity.REQUIRED);

        return hrDept;
    }

    public static AttributeDefinition createRequiredAttrDef(String name,
                                                            IDataType dataType) {
        return new AttributeDefinition(name, dataType.getName(), Multiplicity.REQUIRED, false, null);
    }

    @SuppressWarnings("unchecked")
    protected HierarchicalTypeDefinition<TraitType> createTraitTypeDef(
            String name, ImmutableList<String> superTypes, AttributeDefinition... attrDefs) {
        return new HierarchicalTypeDefinition(TraitType.class, name, superTypes, attrDefs);
    }

    @SuppressWarnings("unchecked")
    protected HierarchicalTypeDefinition<ClassType> createClassTypeDef(
            String name, ImmutableList<String> superTypes, AttributeDefinition... attrDefs) {
        return new HierarchicalTypeDefinition(ClassType.class, name, superTypes, attrDefs);
    }
}
