import '@fastify/jwt';
import type { FastifyReply, FastifyRequest } from 'fastify';

export type Role = 'teacher' | 'student' | 'admin';

declare module '@fastify/jwt' {
  interface FastifyJWT {
    payload: { sub: string; role: Role; studentId?: string };
    user: { sub: string; role: Role; studentId?: string };
  }
}

export async function requireSignedIn(request: FastifyRequest, reply: FastifyReply) {
  try {
    await request.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'Unauthorized', message: 'Please sign in again.' });
  }
}

export function requireRole(...roles: Role[]) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    if (!roles.includes(request.user.role)) {
      return reply
        .code(403)
        .send({ error: 'Forbidden', message: 'This account can’t do that.' });
    }
  };
}