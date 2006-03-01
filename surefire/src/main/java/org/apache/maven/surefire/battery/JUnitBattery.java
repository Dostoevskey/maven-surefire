package org.apache.maven.surefire.battery;

/*
 * Copyright 2001-2005 The Codehaus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.surefire.report.ReporterManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

public class JUnitBattery
    extends AbstractBattery
{
    public static final String TEST_CASE = "junit.framework.TestCase";

    public static final String TEST_RESULT = "junit.framework.TestResult";

    public static final String TEST_LISTENER = "junit.framework.TestListener";

    public static final String TEST = "junit.framework.Test";

    public static final String ADD_LISTENER_METHOD = "addListener";

    public static final String RUN_METHOD = "run";

    public static final String COUNT_TEST_CASES_METHOD = "countTestCases";

    public static final String SETUP_METHOD = "setUp";

    public static final String TEARDOWN_METHOD = "tearDown";

    private static final String TEST_SUITE = "junit.framework.TestSuite";

    private Object testObject;

    private Class[] interfacesImplementedByDynamicProxy;

    private Class testResultClass;

    private ClassLoader classLoader;

    private Class testClass;

    private Method addListenerMethod;

    private Method countTestCasesMethod;

    private Method runMethod;

    public JUnitBattery( String testClassName )
        throws Exception
    {
        processTestClass( getClass().getClassLoader().loadClass( testClassName ), getClass().getClassLoader() );
    }

    public JUnitBattery( final String testClass, ClassLoader loader )
        throws Exception
    {
        processTestClass( loader.loadClass( testClass ), loader );
    }

    public JUnitBattery( final Class testClass, ClassLoader loader )
        throws Exception
    {
        processTestClass( testClass, loader );
    }

    public void processTestClass( final Class testClass, ClassLoader loader )
        throws Exception
    {
        if ( testClass == null )
        {
            throw new NullPointerException( "testClass is null" );
        }

        if ( loader == null )
        {
            throw new NullPointerException( "classLoader is null" );
        }

        this.classLoader = loader;

        this.testClass = testClass;

        testResultClass = loader.loadClass( TEST_RESULT );

        Class testCaseClass = loader.loadClass( TEST_CASE );

        Class testSuiteClass = loader.loadClass( TEST_SUITE );

        Class testListenerInterface = loader.loadClass( TEST_LISTENER );

        Class testInterface = loader.loadClass( TEST );

        // ----------------------------------------------------------------------
        // Strategy for executing JUnit tests
        //
        // o look for the suite method and if that is present execute that method
        //   to get the test object.
        //
        // o look for test classes that are assignable from TestCase
        //
        // o look for test classes that only implement the Test interface
        // ----------------------------------------------------------------------

        try
        {
            Class[] emptyArgs = new Class[0];

            Method suiteMethod = testClass.getMethod( "suite", emptyArgs );

            if ( Modifier.isPublic( suiteMethod.getModifiers() ) && Modifier.isStatic( suiteMethod.getModifiers() ) )
            {
                testObject = suiteMethod.invoke( null, emptyArgs );
            }
        }
        catch ( NoSuchMethodException e )
        {
        }

        if ( testObject == null && testCaseClass.isAssignableFrom( testClass ) )
        {
            Class[] constructorParamTypes = {Class.class};

            Constructor constructor = testSuiteClass.getConstructor( constructorParamTypes );

            Object[] constructorParams = {testClass};

            testObject = constructor.newInstance( constructorParams );
        }

        if ( testObject == null )
        {
            Constructor testConstructor =  getTestConstructor( testClass );

            if ( testConstructor.getParameterTypes().length == 0 )
            {
                testObject = testConstructor.newInstance( new Object[0] );
            }
            else
            {
                testObject = testConstructor.newInstance( new Object[]{ testClass.getName() } );
            }
        }

        interfacesImplementedByDynamicProxy = new Class[1];

        interfacesImplementedByDynamicProxy[0] = testListenerInterface;

        // The interface implemented by the dynamic proxy (TestListener), happens to be
        // the same as the param types of TestResult.addTestListener
        Class[] addListenerParamTypes = interfacesImplementedByDynamicProxy;

        addListenerMethod = testResultClass.getMethod( ADD_LISTENER_METHOD, addListenerParamTypes );

        if ( testInterface.isAssignableFrom( testClass ) )//testObject.getClass() ) )
        {
            countTestCasesMethod = testInterface.getMethod( COUNT_TEST_CASES_METHOD, new Class[0] );

	        runMethod = testInterface.getMethod( RUN_METHOD, new Class[] { testResultClass } );

        }
        else
        {
            try
            {
                countTestCasesMethod = testClass.getMethod( COUNT_TEST_CASES_METHOD, new Class[0] );
			}
			catch (Exception e)
			{
				countTestCasesMethod = null; // for clarity
			}

			try
			{
				runMethod = testClass.getMethod( RUN_METHOD, new Class[] { testResultClass } );
            }
            catch (Exception e)
            {
				runMethod = null;	// for clarity
            }
        }
    }

    public Class getTestClass()
    {
        return testClass;
    }

	protected Object getTestClassInstance()
	{
		return testObject;
	}

	public void execute( ReporterManager reportManager )
		throws Exception
	{
		if ( runMethod != null )
		{
			executeJUnit( reportManager );
		}
		else
		{
			super.execute( reportManager );
		}
	}

    protected void executeJUnit( ReporterManager reportManager )
    {
        try
        {
            Object instanceOfTestResult = testResultClass.newInstance();

            TestListenerInvocationHandler invocationHandler =
                new TestListenerInvocationHandler( reportManager, instanceOfTestResult, classLoader );

            Object testListener =
                Proxy.newProxyInstance( classLoader, interfacesImplementedByDynamicProxy, invocationHandler );

            Object[] addTestListenerParams = {testListener};

            addListenerMethod.invoke( instanceOfTestResult, addTestListenerParams );

            Object[] runParams = {instanceOfTestResult};

            runMethod.invoke( testObject, runParams );
        }
        catch ( IllegalArgumentException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
        catch ( InstantiationException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
        catch ( IllegalAccessException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
    }

    public int getTestCount()
    {
        try
        {
            if ( countTestCasesMethod != null)
            {
                Integer integer = (Integer) countTestCasesMethod.invoke( testObject, new Class[0] );

                return integer.intValue();
            }
            else
            {
                return super.getTestCount();
            }
        }
        catch ( IllegalAccessException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
        catch ( IllegalArgumentException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new org.apache.maven.surefire.battery.assertion.BatteryTestFailedException( testObject.getClass().getName(), e );
        }
    }

    public String getBatteryName()
    {
        return testClass.getName();
        //return testClass.getPackage().getName();
    }
    
    protected Constructor getTestConstructor( Class testClass )
        throws NoSuchMethodException
    {
        Class[] params = { String.class };

        try
        {
            return testClass.getConstructor( params );
        }
        catch ( NoSuchMethodException e )
        {
            return testClass.getConstructor( new Class[0] );
        }
    }
}
