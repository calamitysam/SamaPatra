import Fastify from 'fastify';
import cors from '@fastify/cors';
import multipart from '@fastify/multipart';
import { pool } from './db/index.js';
import jwt from '@fastify/jwt';
import authRoutes from './routes/auth.js';
import rosterRoutes from './routes/roster.js';
import { requireSignedIn, requireRole } from './auth/guards.js';
import meRoutes from './routes/me.js';
import classPapersRoutes from './routes/classPapers.js';

// Import routes
import questionRoutes from './routes/questions.js';
import adaptiveTestRoutes from './routes/adaptiveTest.js';
import paperRoutes from './routes/papers.js';
import gradingRoutes from './routes/grading.js';
import studentRoutes from './routes/students.js';
import classRoutes from './routes/classes.js';
import bankRoutes from './routes/bank.js';

const server = Fastify({
  logger: {
    level: process.env.LOG_LEVEL || 'info',
    transport:
      process.env.NODE_ENV === 'development'
        ? {
            target: 'pino-pretty',
            options: {
              colorize: true,
              translateTime: 'HH:MM:ss',
              ignore: 'pid,hostname',
            },
          }
        : undefined,
  },
});

// Register plugins
await server.register(cors, {
  origin: process.env.CORS_ORIGIN || '*',
  credentials: true,
});

await server.register(multipart, {
  limits: {
    fileSize: 10 * 1024 * 1024, // 10MB for PDF uploads
  },
});
const jwtSecret = process.env.JWT_SECRET;
if (!jwtSecret || jwtSecret.length < 32) {
  throw new Error('JWT_SECRET is missing or too short. Add a random 32+ character value to .env');
}
await server.register(jwt, { secret: jwtSecret });

// Register routes
// Public: sign up and sign in
await server.register(authRoutes, { prefix: '/api' });

// Everything else needs a signed-in user
await server.register(
  async (app) => {
    app.addHook('onRequest', requireSignedIn);

    // Teachers and students
    await app.register(adaptiveTestRoutes);
    await app.register(paperRoutes);
    await app.register(studentRoutes);
    await app.register(meRoutes);

    // Teachers only
    await app.register(async (teacherApp) => {
      teacherApp.addHook('onRequest', requireRole('teacher', 'admin'));
      await teacherApp.register(questionRoutes);
      await teacherApp.register(gradingRoutes);
      await teacherApp.register(classRoutes);
      await teacherApp.register(rosterRoutes);
      await teacherApp.register(classPapersRoutes);
      await teacherApp.register(bankRoutes);
    });
  },
  { prefix: '/api' }
);

// Health check
server.get('/health', async (request, reply) => {
  try {
    await pool.query('SELECT 1');
    return { status: 'ok', timestamp: new Date().toISOString() };
  } catch (error) {
    reply.code(503);
    return { status: 'error', error: 'Database unavailable' };
  }
});

// Root route
server.get('/', async (request, reply) => {
  return {
    name: 'SamaPatra API',
    version: '1.0.0',
    description: 'Inclusive exam platform for students with diverse learning needs',
  };
});

// Error handler
server.setErrorHandler((error, request, reply) => {
  server.log.error(error);

  const statusCode = error.statusCode || 500;
  reply.code(statusCode).send({
    error: error.name,
    message: error.message,
    statusCode,
  });
});

// Graceful shutdown
const shutdown = async () => {
  server.log.info('Shutting down gracefully...');
  await server.close();
  await pool.end();
  process.exit(0);
};

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

// Start server
const start = async () => {
  try {
    const port = parseInt(process.env.PORT || '3000');
    const host = process.env.HOST || '0.0.0.0';

    await server.listen({ port, host });
    server.log.info(`Server listening on ${host}:${port}`);
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
};

start();
